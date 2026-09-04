package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.TestGraph
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.OpenAICompatibleConfig
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.OpenAiCompatibleModelProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration test (IVI-IVAI-DSN-CR-004): agent-core → OpenAiCompatibleModelProvider
 * (MockWebServer OpenAI Chat Completions) → unified ModelResponse → Schema / Policy
 * → Mock vehicle tool → SUCCEEDED. Mirrors the Ollama chain through the OpenAI
 * compatible backend.
 */
class AgentOpenAiCompatibleIntegrationTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueChat(content: String) {
        // SSE streaming frames: split content into small deltas, finish frame, [DONE].
        val parts = content.chunked(content.length / 3 + 1).filter { it.isNotEmpty() }
        val sb = StringBuilder()
        parts.forEachIndexed { i, part ->
            val escaped = Json.encodeToString(part) // JSON string escape
            sb.append("data: ").append("""{"id":"chatcmpl-it","model":"qwen3.5:4b","choices":[{"delta":{"content":$escaped}}]}""").append("\n\n")
        }
        sb.append("data: ").append("""{"id":"chatcmpl-it","choices":[{"delta":{},"finish_reason":"stop"}]}""").append("\n\n")
        sb.append("data: [DONE]").append("\n\n")
        server.enqueue(MockResponse().setResponseCode(200).setBody(sb.toString()))
    }

    @Test
    fun `openai compatible provider drives the full agent tool chain`() = runBlocking {
        enqueueChat(
            """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power_on","functionId":"AC_Control_1","arguments":{"position":"driver"}}],"modelConfidence":0.98,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"EXPLICIT_INTENT"}"""
        )

        val provider = OpenAiCompatibleModelProvider(
            OpenAICompatibleConfig(baseUrl = server.url("/").toString())
        )
        val (workflow, adapter) = TestGraph.build(provider)

        val result = workflow.process(AgentInput("req-oai-1", "打开空调", turnId = "turn-oai-1"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertTrue(adapter.state.powerOn, "Mock 空调应已打开")

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)

        // 分段性能与统一错误码
        assertNotNull(result.performance)
        assertTrue(result.performance!!.endToEndMs >= 0)
        assertTrue(result.performance!!.modelCallTotalMs != null)
        assertNotNull(result.performance!!.unattributedMs)
    }

    @Test
    fun `openai compatible invalid response maps to MODEL parse failure`() = runBlocking {
        // Streaming body without any delta content → provider throws INVALID_RESPONSE → TurnFailed.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """data: {"id":"x","choices":[{"delta":{},"finish_reason":"stop"}]}

              data: [DONE]

"""
                )
        )

        val provider = OpenAiCompatibleModelProvider(
            OpenAICompatibleConfig(baseUrl = server.url("/").toString())
        )
        val (workflow, adapter) = TestGraph.build(provider)

        val result = workflow.process(AgentInput("req-oai-2", "打开空调", turnId = "turn-oai-2"), Session())

        assertEquals(AgentState.FAILED, result.state)
        assertTrue(!adapter.state.powerOn, "无效响应不得执行工具")
        assertEquals("IVAI-MODEL-001", result.errorCode)
    }
}
