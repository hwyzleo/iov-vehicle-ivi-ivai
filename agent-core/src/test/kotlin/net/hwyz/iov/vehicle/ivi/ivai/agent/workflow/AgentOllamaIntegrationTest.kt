package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockClimateToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentRoute
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.TestGraph
import net.hwyz.iov.vehicle.ivi.ivai.model.OllamaConfig
import net.hwyz.iov.vehicle.ivi.ivai.model.OllamaModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.observability.CollectingTelemetryRecorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Full-stack JVM integration test:
 * agent-core → OllamaModelProvider → Mac Ollama (qwen3.5:4b) → MockClimateToolAdapter.
 * Skips gracefully when Ollama is not reachable at localhost:11434.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentOllamaIntegrationTest {

    private val telemetry = CollectingTelemetryRecorder()

    @BeforeAll
    fun requireOllama() {
        assumeTrue(ollamaReachable(), "Mac Ollama not reachable at localhost:11434 — skipping integration tests")
    }

    private fun freshGraph(): Pair<AgentWorkflow, MockClimateToolAdapter> {
        val adapter = MockClimateToolAdapter()
        val provider = OllamaModelProvider(
            OllamaConfig(baseUrl = "http://localhost:11434", model = "qwen3.5:4b")
        )
        val (wf, ad) = TestGraph.build(
            model = provider,
            telemetry = telemetry,
            config = AgentConfig(model = "qwen3.5:4b", ollamaBaseUrl = "http://localhost:11434", requestTimeoutMs = 60_000),
            adapter = adapter
        )
        return wf to ad
    }

    @Test
    fun `打开空调 executes power_on end to end`() = runBlocking {
        val (workflow, adapter) = freshGraph()
        val result = workflow.process(input("打开空调"), Session())
        println("[itg] power_on => state=${result.state} route=${result.route} exec=${result.executionResult?.toolId} error=${result.errorCode} resp=${result.responseText}")
        println("[itg] raw=${result.rawModelContent}")
        assertEquals(AgentState.SUCCEEDED, result.state)
        assertTrue(adapter.state.powerOn)
        assertNotNull(result.executionResult)
    }

    @Test
    fun `我有点冷 maps to temperature tool not power_on`() = runBlocking {
        val (workflow, adapter) = freshGraph()
        val result = workflow.process(input("我有点冷"), Session())
        println("[itg] cold => state=${result.state} exec=${result.executionResult?.toolId} error=${result.errorCode} resp=${result.responseText}")
        assertEquals(AgentState.SUCCEEDED, result.state)
        val tool = result.executionResult?.toolId
        assertTrue(
            tool == "climate.temperature_increase" || tool == "climate.temperature_set",
            "expected a temperature tool, got $tool"
        )
        assertTrue(tool != "climate.power_on")
    }

    @Test
    fun `温度调到 without value asks for the missing slot`() = runBlocking {
        val (workflow, adapter) = freshGraph()
        val result = workflow.process(input("温度调到"), Session())
        println("[itg] missing-slot => state=${result.state} route=${result.route} error=${result.errorCode} resp=${result.responseText}")
        println("[itg] raw=${result.rawModelContent}")
        assertTrue(
            result.state == AgentState.WAITING_USER || result.route == AgentRoute.LOCAL_DIALOGUE,
            "expected dialogue/waits, got ${result.state}"
        )
        assertNull(result.executionResult)
        assertNull(adapter.state.lastExecution)
    }

    @Test
    fun `补充 24 度 resumes the pending temperature task`() = runBlocking {
        val (workflow, adapter) = freshGraph()
        val session = Session()
        val first = workflow.process(input("温度调到"), session)
        println("[itg] resume#1 => ${first.state} resp=${first.responseText}")
        val second = workflow.process(input("24度"), session)
        println("[itg] resume#2 => state=${second.state} exec=${second.executionResult?.toolId} error=${second.errorCode} resp=${second.responseText}")
        println("[itg] raw=${second.rawModelContent}")
        assertTrue(
            second.state == AgentState.SUCCEEDED || second.route == AgentRoute.LOCAL_TOOL,
            "expected local tool execution, got ${second.state}"
        )
    }

    @Test
    fun `开放域请求 not executed`() = runBlocking {
        val (workflow, adapter) = freshGraph()
        val result = workflow.process(input("今天天气怎么样"), Session())
        println("[itg] open-domain => state=${result.state} route=${result.route} error=${result.errorCode} resp=${result.responseText}")
        assertNull(result.executionResult)
        assertNull(adapter.state.lastExecution)
    }

    @Test
    fun `触发确认的工具经 confirm 执行一次且重复确认被拒绝`() = runBlocking {
        val (workflow, adapter) = freshGraph()
        val session = Session()
        val result = workflow.process(input("打开空调"), session)
        val confirmationId = session.pendingConfirmationId
        if (confirmationId == null) {
            println("[itg] model did not request confirmation (state=${result.state}) — skipping confirm assertions")
            assumeTrue(result.state == net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.WAITING_USER, "model did not request confirmation")
            return@runBlocking
        }
        println("[itg] confirmation required: id=$confirmationId tool=${session.pendingTask?.intent?.toolId}")
        assertFalse(adapter.state.powerOn, "确认前不得执行工具")

        val approved = workflow.confirm(confirmationId, session)
        println("[itg] confirm => state=${approved.state} exec=${approved.executionResult?.toolId} error=${approved.errorCode}")
        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.SUCCEEDED, approved.state)
        assertTrue(adapter.state.powerOn)
        assertNull(session.pendingTask, "确认后 pending 应被消费")

        val again = workflow.confirm(confirmationId, session)
        println("[itg] double-confirm => state=${again.state}")
        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.REJECTED, again.state, "重复确认应被拒绝")
    }

    private fun input(text: String) = AgentInput(
        requestId = "itg-${UUID.randomUUID()}",
        text = text,
        source = "integration"
    )

    private fun ollamaReachable(): Boolean = try {
        val conn = URL("http://localhost:11434/api/tags").openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    } catch (e: Exception) {
        println("[itg] Ollama not reachable: ${e.message}")
        false
    }
}
