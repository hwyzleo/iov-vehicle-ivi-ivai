package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.CollectingAgentEventListener
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.StubModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.TestGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-010 可观测性补充 · 模型解析失败诊断：
 * 当本地模型返回无法解析的内容（“模型返回格式异常”），失败详情（DebugInfo.
 * errorDetail）必须携带**完整**原始模型返回数据，便于定位具体原因，而不是只
 * 展示截断的异常描述。
 */
class ModelFailureDetailTest {

    @Test
    fun `模型解析失败时失败详情携带完整原始返回`() = runTest {
        val raw = "{\"route\":\"LOCAL_TOOL\",\"intents\":[{\"toolId\":\"climate.temperature_increase\"  // truncated json"
        val stub = StubModelProvider(raw)
        val listener = CollectingAgentEventListener()
        val (workflow, _) = TestGraph.build(stub, eventListener = listener)

        val result = workflow.process(
            AgentInput(requestId = "req-1", text = "我有点冷", source = "mock", turnId = "turn-1"),
            Session()
        )
        assertEquals(AgentState.FAILED, result.state)

        val debugEvent = listener.ofType(AgentEvent.DebugInfo::class.java).lastOrNull()
        assertNotNull(debugEvent, "应发出 DebugInfo 事件")
        val info = (debugEvent as AgentEvent.DebugInfo).debug
        assertNotNull(info.errorDetail, "失败详情不应为空")
        assertTrue(info.errorDetail!!.contains(raw), "失败详情必须包含完整原始模型返回全文")
        assertTrue(info.errorDetail!!.contains("—— 原始返回 ——"), "失败详情应标记原始返回段")
    }
}
