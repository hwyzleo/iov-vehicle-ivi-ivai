package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.CollectingAgentEventListener
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.StreamingStubModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.StubModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.TestGraph
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionContext
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutionResult
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AgentEvent emission + idempotent confirm()/cancel() (IVI-IVAI-DSN-CR-002).
 */
class AgentEventWorkflowTest {

    private val powerOn = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power_on","functionId":"AC_Control_1","arguments":{"position":"driver"}}],"modelConfidence":0.98,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"EXPLICIT_INTENT"}"""
    private val dialogueMissingTemp = """{"route":"LOCAL_DIALOGUE","intents":[{"toolId":"climate.temperature_set","functionId":"AC_Temperature_1","arguments":{"position":"driver"}}],"modelConfidence":0.85,"riskLevel":"medium","needConfirmation":false,"missingArguments":["temperature"],"reasonCode":"MISSING_SLOT"}"""
    private val confirmPowerOn = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power_on","arguments":{"position":"driver"}}],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":true,"missingArguments":[]}"""
    // CR-005: 确认流程经 L1 隐式表达触发（temperature_increase 中等风险）。
    private val confirmIncrease = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.temperature_increase","arguments":{"position":"driver","step":1}}],"modelConfidence":0.9,"riskLevel":"medium","needConfirmation":true,"missingArguments":[]}"""

    // ------------------------------------------------------------------ event emission

    @Test
    fun `工具执行成功按顺序发射事件且不发射 Reply 避免双气泡`() = runTest {
        val listener = CollectingAgentEventListener()
        val stub = StubModelProvider(powerOn)
        val (workflow, adapter) = TestGraph.build(stub, eventListener = listener)
        val result = workflow.process(input("req-1", "打开空调", turnId = "turn-1"), Session())

        assertEquals(ExecutionStatus.SUCCEEDED, result.executionResult!!.status)
        assertTrue(adapter.state.powerOn)

        assertEquals(
            listOf(
                AgentEvent.UserSubmitted::class,
                AgentEvent.ProcessingStarted::class,
                AgentEvent.ToolExecutionStarted::class,
                // 先写测试快照（finish）再发终态事件：DebugInfo 先于 ToolExecutionFinished。
                AgentEvent.DebugInfo::class,
                AgentEvent.ToolExecutionFinished::class
            ),
            listener.events.map { it::class }
        )
        assertTrue(listener.events.none { it is AgentEvent.Reply }, "工具执行回合不应发射 Reply")

        val finished = listener.events.filterIsInstance<AgentEvent.ToolExecutionFinished>().single()
        assertEquals("climate.power_on", finished.toolId)
        assertEquals(ExecutionStatus.SUCCEEDED, finished.status)
        assertFalse(finished.retryable)

        listener.events.forEach { event ->
            assertEquals("req-1", event.requestId)
            assertEquals("turn-1", event.turnId)
        }
    }

    @Test
    fun `缺参数追问发射 Reply 且不执行工具`() = runTest {
        val listener = CollectingAgentEventListener()
        val stub = StubModelProvider(dialogueMissingTemp)
        val (workflow, adapter) = TestGraph.build(stub, eventListener = listener)
        val session = Session()
        val result = workflow.process(input("req-2", "温度调到", turnId = "turn-2"), session)

        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.WAITING_USER, result.state)
        assertNull(adapter.state.lastExecution)

        val reply = listener.events.filterIsInstance<AgentEvent.Reply>().single()
        assertTrue(reply.text.contains("temperature"))
        assertTrue(listener.events.none { it is AgentEvent.ToolExecutionStarted })
        assertNotNull(session.pendingTask)
        assertFalse(session.pendingTask!!.needConfirmation)
    }

    @Test
    fun `模型编造的缺参名不透传-真实缺参以 Schema 为准`() = runTest {
        // 模型声称缺 air_volume_support（编造字段），但真实 Tool schema 缺的是 temperature。
        val fabricated = """{"route":"LOCAL_DIALOGUE","intents":[{"toolId":"climate.temperature_set","arguments":{"position":"driver"}}],"modelConfidence":0.85,"riskLevel":"medium","needConfirmation":false,"missingArguments":["air_volume_support"],"reasonCode":"MISSING_SLOT"}"""
        val listener = CollectingAgentEventListener()
        val (workflow, adapter) = TestGraph.build(StubModelProvider(fabricated), eventListener = listener)
        val session = Session()
        val result = workflow.process(input("req-2b", "空调风量调高一档", turnId = "turn-2b"), session)

        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.WAITING_USER, result.state)
        assertNull(adapter.state.lastExecution)
        val reply = listener.events.filterIsInstance<AgentEvent.Reply>().single()
        // 不透传编造字段 air_volume_support；追问真实必填参数 temperature。
        assertFalse(reply.text.contains("air_volume_support"))
        assertTrue(reply.text.contains("temperature"))
        assertEquals(listOf("temperature"), session.pendingTask!!.missingArguments)
    }

    @Test
    fun `REJECT 分支不展示模型编造的缺参`() = runTest {
        // 模型判定 REJECT 并编造缺参 blower_mode（该字段非任何真实工具参数）。
        val rejectWithFakeMissing = """{"route":"REJECT","intents":[{"toolId":"climate.defrost.set","arguments":{}}],"modelConfidence":0.99,"riskLevel":"high","needConfirmation":false,"missingArguments":["blower_mode"],"reasonCode":"TOOL_NOT_AVAILABLE"}"""
        val listener = CollectingAgentEventListener()
        val (workflow, adapter) = TestGraph.build(StubModelProvider(rejectWithFakeMissing), eventListener = listener)
        val result = workflow.process(input("req-2e", "空调设置成除霜", turnId = "turn-2e"), Session())

        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.REJECTED, result.state)
        assertNull(adapter.state.lastExecution)
        assertTrue(listener.events.filterIsInstance<AgentEvent.Reply>().any { it.text.contains("已拒绝") })
        // 细节面板（DebugInfo）中不得把 blower_mode 展示为缺参。
        val debug = listener.events.filterIsInstance<AgentEvent.DebugInfo>().lastOrNull()
        assertTrue(debug == null || debug.debug.parsed?.missingArguments?.isEmpty() != false,
            "REJECT 不得展示编造缺参: ${debug?.debug?.parsed?.missingArguments}")
    }

    @Test
    fun `模型引用未知工具时拒绝而非追问编造字段`() = runTest {
        // 模型对不存在的座椅按摩工具输出 LOCAL_DIALOGUE，且缺参列表是 toolID。
        val unknownTool = """{"route":"LOCAL_DIALOGUE","intents":[{"toolId":"seat.massage.set","arguments":{}}],"modelConfidence":0.85,"riskLevel":"medium","needConfirmation":false,"missingArguments":["toolID"],"reasonCode":"MISSING_SLOT"}"""
        val listener = CollectingAgentEventListener()
        val (workflow, adapter) = TestGraph.build(StubModelProvider(unknownTool), eventListener = listener)
        val result = workflow.process(input("req-2c", "打开座椅按摩", turnId = "turn-2c"), Session())

        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.REJECTED, result.state)
        assertEquals("IVAI-TOOL-001", result.errorCode)
        assertNull(adapter.state.lastExecution)
        assertTrue(listener.events.none { it is AgentEvent.Reply }, "未知工具不应追问")
        assertTrue(listener.events.filterIsInstance<AgentEvent.TurnFailed>().any { it.errorCode == "IVAI-TOOL-001" })
    }

    @Test
    fun `模型误报缺参而参数已足时改用通用澄清不执行`() = runTest {
        // 参数已足（temperature_set 带 temperature），模型仍 LOCAL_DIALOGUE 并编造缺参。
        val completeArgs = """{"route":"LOCAL_DIALOGUE","intents":[{"toolId":"climate.temperature_set","arguments":{"position":"driver","temperature":26}}],"modelConfidence":0.85,"riskLevel":"medium","needConfirmation":false,"missingArguments":["air_volume_support"],"reasonCode":"MISSING_SLOT"}"""
        val listener = CollectingAgentEventListener()
        val (workflow, adapter) = TestGraph.build(StubModelProvider(completeArgs), eventListener = listener)
        val result = workflow.process(input("req-2d", "空调风量调高一档", turnId = "turn-2d"), Session())

        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.WAITING_USER, result.state)
        val reply = listener.events.filterIsInstance<AgentEvent.Reply>().single()
        assertFalse(reply.text.contains("air_volume_support"))
        assertFalse(reply.text.contains("temperature"), "参数已足不应追问具体字段")
        // 不因误报而执行工具（可能映射到错误工具）。
        assertNull(adapter.state.lastExecution)
        assertTrue(listener.events.none { it is AgentEvent.ToolExecutionStarted })
    }

    @Test
    fun `模型失败发射 TurnFailed 且允许重试`() = runTest {
        val listener = CollectingAgentEventListener()
        val stub = StubModelProvider("this is { not json")
        val (workflow, adapter) = TestGraph.build(stub, eventListener = listener)
        val result = workflow.process(input("req-3", "我有点冷", turnId = "turn-3"), Session())

        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.FAILED, result.state)
        assertEquals("IVAI-MODEL-002", result.errorCode)
        assertNull(adapter.state.lastExecution)

        val failed = listener.events.filterIsInstance<AgentEvent.TurnFailed>().single()
        assertEquals("IVAI-MODEL-002", failed.errorCode)
        assertTrue(failed.retryable)
    }

    @Test
    fun `Schema 校验失败发射 TurnFailed 且不允许重试`() = runTest {
        val listener = CollectingAgentEventListener()
        val stub = StubModelProvider("""[1,2,3]""")
        val (workflow, _) = TestGraph.build(stub, eventListener = listener)
        workflow.process(input("req-4", "我有点冷", turnId = "turn-4"), Session())

        val failed = listener.events.filterIsInstance<AgentEvent.TurnFailed>().single()
        assertEquals("IVAI-SCHEMA-001", failed.errorCode)
        assertFalse(failed.retryable)
    }

    @Test
    fun `工具执行失败发射 ToolExecutionFinished FAILED 且允许重试`() = runTest {
        val listener = CollectingAgentEventListener()
        val failingExecutor = object : ToolExecutor {
            override suspend fun execute(
                toolId: String,
                arguments: Map<String, Any?>,
                context: ExecutionContext
            ): ToolExecutionResult = ToolExecutionResult(
                requestId = context.requestId,
                toolId = toolId,
                status = ExecutionStatus.FAILED,
                message = "模拟执行失败：网络超时",
                errorCode = "IVAI-EXEC-001"
            )
        }
        val stub = StubModelProvider(powerOn)
        val (workflow, _) = TestGraph.build(stub, toolExecutor = failingExecutor, eventListener = listener)
        val result = workflow.process(input("req-5", "打开空调", turnId = "turn-5"), Session())

        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.FAILED, result.state)
        assertEquals("IVAI-EXEC-001", result.errorCode)

        val finished = listener.events.filterIsInstance<AgentEvent.ToolExecutionFinished>().single()
        assertEquals(ExecutionStatus.FAILED, finished.status)
        assertTrue(finished.retryable)
    }

    @Test
    fun `工具执行回合发射包含编排解析工具与统计的 DebugInfo`() = runTest {
        val listener = CollectingAgentEventListener()
        val stub = StubModelProvider(powerOn)
        val (workflow, adapter) = TestGraph.build(stub, eventListener = listener)
        // CR-005: 走 L1 以包含模型调用统计；stub 返回 power_on（候选集合内）→ 仍执行空调开机。
        val result = workflow.process(input("req-d1", "我有点冷", turnId = "turn-d1"), Session())
        assertTrue(adapter.state.powerOn)

        val debug = listener.events.filterIsInstance<AgentEvent.DebugInfo>().single().debug
        assertEquals("turn-d1", debug.turnId)
        assertEquals("req-d1", debug.requestId)

        // 隐私边界（CR-004）：Agent Event 不暴露 System Prompt / 完整请求编排
        // （composedMessages 已从 DebugInfo 移除），提示词只读入口在设置页。

        // 解析结果
        val parsed = debug.parsed
        assertNotNull(parsed)
        assertEquals("LOCAL_TOOL", parsed!!.route)
        assertTrue(parsed.intents.any { it.toolId == "climate.power_on" })

        // 工具执行
        val tool = debug.tool
        assertNotNull(tool)
        assertEquals("climate.power_on", tool!!.toolId)
        assertEquals("SUCCEEDED", tool.status)
        assertTrue(tool.latencyMs != null && tool.latencyMs >= 0)

        // 分段性能（CR-004）：端到端、模型调用与未归因均可用，网络为诊断子指标
        val performance = debug.performance
        assertNotNull(performance)
        assertTrue(performance!!.endToEndMs >= 0)
        val modelTotal = performance.modelCallTotalMs
        assertNotNull(modelTotal)
        assertTrue(requireNotNull(modelTotal) >= 0)
        assertNotNull(performance.unattributedMs)
        assertEquals("SUCCEEDED", debug.state)
        assertEquals("LOCAL_TOOL", debug.route)
        // CR-005: 层级可观测
        assertEquals("L1_LOCAL_TOOL_REASONING", debug.intentTier)
        assertEquals("L1_LOCAL_TOOL_REASONING", debug.finalTier)
        assertEquals("L1_LOCAL_LLM", debug.candidateSource)
    }

    @Test
    fun `模型失败时 DebugInfo 仍携带性能与错误码`() = runTest {
        val listener = CollectingAgentEventListener()
        val stub = StubModelProvider("this is { not json")
        val (workflow, _) = TestGraph.build(stub, eventListener = listener)
        workflow.process(input("req-d2", "我有点冷", turnId = "turn-d2"), Session())

        val debug = listener.events.filterIsInstance<AgentEvent.DebugInfo>().single().debug
        assertEquals("IVAI-MODEL-002", debug.errorCode)
        assertEquals("FAILED", debug.state)
        assertNotNull(debug.performance)
        val modelTotal = debug.performance?.modelCallTotalMs
        assertNotNull(modelTotal)
        assertNull(debug.tool)
    }

    @Test
    fun `流式 Provider 逐块发射 StreamingDelta 且最终结果一致`() = runTest {
        val listener = CollectingAgentEventListener()
        val stub = StreamingStubModelProvider(powerOn)
        val (workflow, adapter) = TestGraph.build(stub, eventListener = listener)
        val result = workflow.process(input("req-str-1", "我有点冷", turnId = "turn-str-1"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertTrue(adapter.state.powerOn)

        val deltas = listener.events.filterIsInstance<AgentEvent.StreamingDelta>()
        assertTrue(deltas.isNotEmpty(), "流式 Provider 应发射增量事件")
        // 增量按顺序累计，最后一条为完整内容
        assertEquals(powerOn, deltas.last().text)
        assertTrue(deltas.first().text.length < powerOn.length)
        // 真·首字延迟进入性能指标
        val ttft = result.performance?.timeToFirstTokenMs
        assertNotNull(ttft)
        assertEquals(true, result.performance?.streamingUsed)
    }

    // ------------------------------------------------------------------ confirmation / cancellation

    @Test
    fun `需要确认时发射 ConfirmationRequired 并保存 confirmationId`() = runTest {
        val listener = CollectingAgentEventListener()
        val stub = StubModelProvider(confirmIncrease)
        val (workflow, adapter) = TestGraph.build(stub, eventListener = listener)
        val session = Session()
        val result = workflow.process(input("req-c1", "我有点冷", turnId = "turn-c1"), session)

        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.WAITING_USER, result.state)
        assertEquals(24.0, adapter.state.driverTemperature, "确认前不得执行工具")

        val confirmation = listener.events.filterIsInstance<AgentEvent.ConfirmationRequired>().single()
        assertEquals("climate.temperature_increase", confirmation.toolId)
        assertTrue(confirmation.confirmationId.isNotBlank())
        assertEquals(confirmation.confirmationId, session.pendingConfirmationId)
        assertEquals("L1_LOCAL_TOOL_REASONING", confirmation.currentTier?.name)
    }

    @Test
    fun `confirm 执行待确认工具且重复确认不重复执行`() = runTest {
        val listener = CollectingAgentEventListener()
        val stub = StubModelProvider(confirmIncrease)
        val (workflow, adapter) = TestGraph.build(stub, eventListener = listener)
        val session = Session()
        workflow.process(input("req-c2", "我有点冷", turnId = "turn-c2"), session)

        val confirmationId = session.pendingConfirmationId!!
        val first = workflow.confirm(confirmationId, session)
        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.SUCCEEDED, first.state)
        assertEquals(25.0, adapter.state.driverTemperature)
        assertNull(session.pendingTask, "确认后 pending 应被消费")

        // Second confirm with the same id must be rejected and must not re-execute.
        val eventsBefore = listener.events.size
        val second = workflow.confirm(confirmationId, session)
        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.REJECTED, second.state)
        assertEquals(eventsBefore, listener.events.size, "拒绝不应发射新事件")
        assertEquals(1, listener.events.filterIsInstance<AgentEvent.ToolExecutionStarted>().size)
    }

    @Test
    fun `cancel 终止待确认任务并发射 TurnCancelled 且不执行`() = runTest {
        val listener = CollectingAgentEventListener()
        val stub = StubModelProvider(confirmIncrease)
        val (workflow, adapter) = TestGraph.build(stub, eventListener = listener)
        val session = Session()
        workflow.process(input("req-c3", "我有点冷", turnId = "turn-c3"), session)

        val confirmationId = session.pendingConfirmationId!!
        val result = workflow.cancel(confirmationId, session)

        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.REJECTED, result.state)
        assertEquals(24.0, adapter.state.driverTemperature, "取消后不得执行工具")
        assertNull(session.pendingTask)

        val cancelled = listener.events.filterIsInstance<AgentEvent.TurnCancelled>().single()
        assertTrue(cancelled.text.contains("已取消"))
        assertEquals("turn-c3", cancelled.turnId)
        assertEquals("req-c3", cancelled.requestId)
    }

    @Test
    fun `不匹配或过期的 confirmationId 被拒绝且不触发工具执行`() = runTest {
        val listener = CollectingAgentEventListener()
        val stub = StubModelProvider(confirmIncrease)
        val (workflow, adapter) = TestGraph.build(stub, eventListener = listener)
        val session = Session()
        workflow.process(input("req-c4", "我有点冷", turnId = "turn-c4"), session)

        val result = workflow.confirm("stale-id", session)
        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.REJECTED, result.state)
        assertEquals(24.0, adapter.state.driverTemperature, "不匹配的 confirmationId 不得执行工具")
        assertNotNull(session.pendingTask, "拒绝不应消费 pending")
        assertTrue(listener.events.none { it is AgentEvent.ToolExecutionStarted })

        // A follow-up cancellation with the same stale id is also rejected.
        val cancelResult = workflow.cancel("stale-id", session)
        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState.REJECTED, cancelResult.state)
    }

    private fun input(requestId: String, text: String, turnId: String) =
        AgentInput(requestId = requestId, text = text, source = "mock", turnId = turnId)
}
