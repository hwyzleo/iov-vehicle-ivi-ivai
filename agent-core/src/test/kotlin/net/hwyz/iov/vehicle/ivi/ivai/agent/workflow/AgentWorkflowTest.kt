package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockClimateToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentRoute
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.StubModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.TestGraph
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelClientException
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelErrorKind
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.observability.CollectingTelemetryRecorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * EARS acceptance scenarios (IVI-IVAI-SPEC需求) driven by a deterministic stub model.
 */
class AgentWorkflowTest {

    private val powerOn = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power_on","functionId":"AC_Control_1","arguments":{"position":"driver"}}],"modelConfidence":0.98,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"EXPLICIT_INTENT"}"""
    private val temperatureIncrease = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.temperature_increase","functionId":"AC_Temperature_2","arguments":{"position":"driver","step":1}}],"modelConfidence":0.9,"riskLevel":"medium","needConfirmation":false,"missingArguments":[],"reasonCode":"IMPLICIT_COLD_INTENT"}"""
    private val dialogueMissingTemp = """{"route":"LOCAL_DIALOGUE","intents":[{"toolId":"climate.temperature_set","functionId":"AC_Temperature_1","arguments":{"position":"driver"}}],"modelConfidence":0.85,"riskLevel":"medium","needConfirmation":false,"missingArguments":["temperature"],"reasonCode":"MISSING_SLOT"}"""
    private val setTemp24 = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.temperature_set","functionId":"AC_Temperature_1","arguments":{"temperature":24}}],"modelConfidence":0.95,"riskLevel":"medium","needConfirmation":false,"missingArguments":[],"reasonCode":"SLOT_FILLED"}"""

    @Test
    fun `打开空调 executes power_on via mock adapter`() = runTest {
        val stub = StubModelProvider(powerOn)
        val (workflow, adapter) = TestGraph.build(stub)
        val result = workflow.process(input("req-1", "打开空调"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertEquals(AgentRoute.LOCAL_TOOL, result.route)
        assertTrue(adapter.state.powerOn)
        assertEquals("climate.power_on", result.executionResult!!.toolId)
        assertEquals(true, result.executionResult.stateChanges["powerOn"])
    }

    @Test
    fun `我有点冷 prefers temperature_increase and never power_on`() = runTest {
        val stub = StubModelProvider(temperatureIncrease)
        val (workflow, adapter) = TestGraph.build(stub)
        val result = workflow.process(input("req-2", "我有点冷"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertEquals("climate.temperature_increase", result.executionResult!!.toolId)
        assertFalse(adapter.state.powerOn)
        assertEquals(25.0, adapter.state.driverTemperature)
    }

    @Test
    fun `温度调到 without value enters LOCAL_DIALOGUE and does not execute`() = runTest {
        val stub = StubModelProvider(dialogueMissingTemp)
        val (workflow, adapter) = TestGraph.build(stub)
        val session = Session()
        val result = workflow.process(input("req-3", "温度调到"), session)

        assertEquals(AgentState.WAITING_USER, result.state)
        assertEquals(AgentRoute.LOCAL_DIALOGUE, result.route)
        assertTrue(result.responseText.contains("temperature"))
        assertNull(adapter.state.lastExecution)
        assertNull(result.executionResult)
        assertNotNull(session.pendingTask)
        assertEquals("climate.temperature_set", session.pendingTask!!.intent.toolId)
    }

    @Test
    fun `补充温度后恢复温度设置任务并生成合法参数`() = runTest {
        // CR-019：“温度调到”语义歧义（AMBIGUOUS → NEED_DIALOGUE），L1 追问；
        // 补参轮次消费模型输出后恢复执行（不因歧义直接编造目标温度）。
        val stub = StubModelProvider(dialogueMissingTemp, setTemp24)
        val (workflow, adapter) = TestGraph.build(stub)
        val session = Session()

        val first = workflow.process(input("req-3", "温度调到"), session)
        assertEquals(AgentState.WAITING_USER, first.state)

        val second = workflow.process(input("req-4", "24度"), session)
        assertEquals(AgentState.SUCCEEDED, second.state)
        assertEquals("climate.temperature_set", second.executionResult!!.toolId)
        assertEquals(24.0, adapter.state.driverTemperature)
        assertNull(session.pendingTask)
    }

    @Test
    fun `多意图请求返回 CLOUD_AI 不执行`() = runTest {
        val cloud = """{"route":"CLOUD_AI","intents":[],"modelConfidence":0.7,"riskLevel":"medium","needConfirmation":false,"missingArguments":[],"reasonCode":"MULTI_INTENT"}"""
        val stub = StubModelProvider(cloud)
        val (workflow, adapter) = TestGraph.build(stub)
        val result = workflow.process(input("req-5", "打开空调然后把温度调到26度"), Session())

        assertEquals(AgentState.CLOUD_REQUIRED, result.state)
        assertEquals(AgentRoute.CLOUD_AI, result.route)
        assertNull(result.executionResult)
        assertNull(adapter.state.lastExecution)
    }

    @Test
    fun `开放域请求返回 CLOUD_AI`() = runTest {
        val open = """{"route":"CLOUD_AI","intents":[],"modelConfidence":0.6,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"OPEN_DOMAIN"}"""
        val stub = StubModelProvider(open)
        val (workflow, adapter) = TestGraph.build(stub)
        val result = workflow.process(input("req-5b", "今天天气怎么样"), Session())
        assertEquals(AgentState.CLOUD_REQUIRED, result.state)
        assertNull(adapter.state.lastExecution)
    }

    @Test
    fun `驾驶安全请求返回 REJECT`() = runTest {
        val reject = """{"route":"REJECT","intents":[],"modelConfidence":0.99,"riskLevel":"high","needConfirmation":false,"missingArguments":[],"reasonCode":"DRIVING_SAFETY"}"""
        val stub = StubModelProvider(reject)
        val (workflow, adapter) = TestGraph.build(stub)
        val result = workflow.process(input("req-6", "帮我把车开走"), Session())
        assertEquals(AgentState.REJECTED, result.state)
        assertEquals(AgentRoute.REJECT, result.route)
        assertNull(adapter.state.lastExecution)
    }

    @Test
    fun `未知工具被阻止执行并返回 IVAI-TOOL-001`() = runTest {
        val unknown = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.ghost","arguments":{}}],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":false,"missingArguments":[]}"""
        val stub = StubModelProvider(unknown)
        val (workflow, adapter) = TestGraph.build(stub)
        // CR-005: “我有点冷”走 L1，模型候选仍会被白名单校验拦截。
        val result = workflow.process(input("req-7", "我有点冷"), Session())

        assertEquals(AgentState.REJECTED, result.state)
        assertEquals("IVAI-TOOL-001", result.errorCode)
        assertNull(result.executionResult)
        assertFalse(adapter.state.powerOn)
        assertTrue(result.validationIssues.any { it.toolId == "climate.ghost" })
    }

    @Test
    fun `缺少必填参数被阻止执行并返回 IVAI-TOOL-002`() = runTest {
        val missing = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.temperature_set","arguments":{}}],"modelConfidence":0.8,"riskLevel":"medium","needConfirmation":false,"missingArguments":[]}"""
        val stub = StubModelProvider(missing)
        val (workflow, adapter) = TestGraph.build(stub)
        val result = workflow.process(input("req-8", "我有点冷"), Session())

        assertEquals(AgentState.REJECTED, result.state)
        assertEquals("IVAI-TOOL-002", result.errorCode)
        assertNull(result.executionResult)
        assertNull(adapter.state.lastExecution)
    }

    @Test
    fun `非法 JSON 返回 IVAI-MODEL-002`() = runTest {
        val stub = StubModelProvider("this is { not json")
        val (workflow, adapter) = TestGraph.build(stub)
        val result = workflow.process(input("req-9", "我有点冷"), Session())

        assertEquals(AgentState.FAILED, result.state)
        assertEquals("IVAI-MODEL-002", result.errorCode)
        assertNull(adapter.state.lastExecution)
    }

    @Test
    fun `输出不符合 Schema 返回 IVAI-SCHEMA-001`() = runTest {
        val stub = StubModelProvider("""[1,2,3]""")
        val (workflow, adapter) = TestGraph.build(stub)
        val result = workflow.process(input("req-10", "我有点冷"), Session())

        assertEquals(AgentState.FAILED, result.state)
        assertEquals("IVAI-SCHEMA-001", result.errorCode)
        assertNull(adapter.state.lastExecution)
    }

    @Test
    fun `未知路由返回 IVAI-ROUTE-001`() = runTest {
        val badRoute = """{"route":"FLY_TO_MOON","intents":[],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":false,"missingArguments":[]}"""
        val stub = StubModelProvider(badRoute)
        val (workflow, adapter) = TestGraph.build(stub)
        val result = workflow.process(input("req-11", "我有点冷"), Session())

        assertEquals(AgentState.REJECTED, result.state)
        assertEquals("IVAI-ROUTE-001", result.errorCode)
        assertNull(adapter.state.lastExecution)
    }

    @Test
    fun `模型不可达返回 IVAI-MODEL-001`() = runTest {
        val stub = object : ModelProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                throw ModelClientException(ModelErrorKind.NETWORK_UNAVAILABLE, message = "connection refused")
            }
        }
        val (workflow, adapter) = TestGraph.build(stub)
        // CR-005: “我有点冷”走 L1 才会调用模型，从而触发模型不可达。
        val result = workflow.process(input("req-12", "我有点冷"), Session())

        assertEquals(AgentState.FAILED, result.state)
        assertEquals("IVAI-MODEL-001", result.errorCode)
        assertNull(adapter.state.lastExecution)
    }

    @Test
    fun `同一 requestId 重复提交不会重复执行`() = runTest {
        val telemetry = CollectingTelemetryRecorder()
        val stub = StubModelProvider(powerOn, powerOn)
        val (workflow, adapter) = TestGraph.build(stub, telemetry = telemetry)
        val session = Session()

        val first = workflow.process(input("req-same", "打开空调"), session)
        assertEquals(AgentState.SUCCEEDED, first.state)
        assertFalse(first.replayed)

        val second = workflow.process(input("req-same", "打开空调"), session)
        assertEquals(AgentState.SUCCEEDED, second.state)
        assertTrue(second.replayed)

        val executing = telemetry.collected.count { it.state == AgentState.EXECUTING.name }
        assertEquals(1, executing)
    }

    @Test
    fun `不同 requestId 同一意图可以再次执行`() = runTest {
        val telemetry = CollectingTelemetryRecorder()
        val stub = StubModelProvider(powerOn, powerOn)
        val (workflow, adapter) = TestGraph.build(stub, telemetry = telemetry)
        val session = Session()

        workflow.process(input("req-a", "打开空调"), session)
        val second = workflow.process(input("req-b", "打开空调"), session)

        assertEquals(AgentState.SUCCEEDED, second.state)
        assertFalse(second.replayed)
        val executing = telemetry.collected.count { it.state == AgentState.EXECUTING.name }
        assertEquals(2, executing)
    }

    @Test
    fun `确认后执行之前需要确认的工具`() = runTest {
        // CR-005: L0 仅放行低风险唯一指令；确认流程通过 L1 隐式表达验证（模型请求确认）。
        val confirm = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.temperature_increase","arguments":{"position":"driver","step":1}}],"modelConfidence":0.9,"riskLevel":"medium","needConfirmation":true,"missingArguments":[]}"""
        val stub = StubModelProvider(confirm)
        val (workflow, adapter) = TestGraph.build(stub)
        val session = Session()

        val ask = workflow.process(input("req-c1", "我有点冷"), session)
        assertEquals(AgentState.WAITING_USER, ask.state)
        assertTrue(ask.responseText.contains("确认"))
        assertEquals(24.0, adapter.state.driverTemperature)

        val approved = workflow.process(input("req-c2", "确认"), session)
        assertEquals(AgentState.SUCCEEDED, approved.state)
        assertEquals(25.0, adapter.state.driverTemperature)
    }

    private fun input(requestId: String, text: String) =
        AgentInput(requestId = requestId, text = text, source = "mock")
}
