package net.hwyz.iov.vehicle.ivi.ivai.model.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.hwyz.iov.vehicle.ivi.ivai.model.ChatMessage
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Ollama streaming contract (NDJSON, stream=true): deltas are forwarded in
 * order, time-to-first-token is client-observed, and the final line's
 * eval_count / durations surface as [net.hwyz.iov.vehicle.ivi.ivai.model.ProviderComputeMetrics].
 */
class OllamaModelProviderStreamingTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OllamaModelProvider

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OllamaModelProvider(
            OllamaConfig(baseUrl = server.url("/").toString())
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun line(content: String, done: Boolean = false): String {
        val escaped = JsonPrimitive(content).toString()
        val stats = if (done) {
            ""","done_reason":"stop","eval_count":12,"eval_duration":500000000,"prompt_eval_count":30,"prompt_eval_duration":200000000,"total_duration":800000000"""
        } else ""
        return """{"model":"qwen3.5:4b","message":{"role":"assistant","content":$escaped},"done":$done$stats}"""
    }

    @Test
    fun `streams ndjson deltas and reports ttft plus server compute`() = runBlocking {
        val full = """{"route":"LOCAL_TOOL","intents":[]}"""
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                buildString {
                    appendLine(line(full.take(10)))
                    appendLine(line(full.drop(10)))
                    appendLine(line("", done = true))
                }
            )
        )

        val deltas = mutableListOf<String>()
        val response = provider.generateStreaming(
            ModelRequest("req-s1", "qwen3.5:4b", listOf(ChatMessage("user", "hi")), timeoutMs = 5_000)
        ) { delta -> deltas += delta }

        assertEquals(listOf(full.take(10), full.drop(10)), deltas)
        assertEquals(full, response.content)
        assertEquals("LOCAL_TOOL", response.contentJson!!.jsonObject["route"]!!.jsonPrimitive.content)
        assertEquals("stop", response.finishReason)
        assertNotNull(response.timeToFirstTokenMs)
        assertTrue(response.timeToFirstTokenMs!! >= 0)
        assertEquals(false, response.network?.connectionReused)

        val compute = response.providerCompute
        assertNotNull(compute)
        assertEquals(12L, compute!!.generationTokens)
        assertEquals(30L, compute.promptEvaluationTokens)
        assertEquals(500L, compute.generationMs)
        assertEquals(200L, compute.promptEvaluationMs)
    }

    @Test
    fun `sends stream true in the payload`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(buildString {
                appendLine(line("x"))
                appendLine(line("", done = true))
            })
        )
        provider.generateStreaming(
            ModelRequest("req-s2", "qwen3.5:4b", listOf(ChatMessage("user", "hi")), timeoutMs = 5_000)
        ) { }

        val recorded = server.takeRequest()
        assertTrue(recorded.body.readUtf8().contains("\"stream\":true"))
    }
}
