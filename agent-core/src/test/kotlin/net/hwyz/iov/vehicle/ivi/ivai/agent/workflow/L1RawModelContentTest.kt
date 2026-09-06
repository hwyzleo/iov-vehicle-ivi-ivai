package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.test.runTest
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
 * CR-010 可观测性补充 · L1 本地模型原始响应：
 * 「我感觉有点冷」走 L1 本地模型，模型误报缺参（climate.power.set 上编造
 * temperature/mode 缺参）→ 真实缺参为空 → 通用澄清“需要更多信息”。此时
 * DebugInfo 必须保留 LLM 完整原始响应（rawModelContent），供执行路径核对
 * 模型输出与匹配资产/解析结果不一致的原因。
 */
class L1RawModelContentTest {

    @Test
    fun `L1 模型误报缺参时保留 LLM 原始响应并通用澄清`() = runTest {
        val raw = """{"route":"LOCAL_DIALOGUE","intents":[{"toolId":"climate.power.set","arguments":{"enabled":true}}],"missingArguments":["temperature","mode"],"modelConfidence":0.8,"riskLevel":"low","needConfirmation":false,"reasonCode":"MISSING_SLOT"}"""
        val stub = StubModelProvider(raw)
        val listener = CollectingAgentEventListener()
        val (workflow, _) = TestGraph.buildGovernedStubGraph(stub, eventListener = listener)

        val result = workflow.process(
            AgentInput(requestId = "req-1", text = "我感觉有点冷", source = "mock", turnId = "turn-1"),
            Session()
        )

        // 模型误报缺参（temperature/mode 不在 power.set 必填中）→ 真实缺参为空 → 通用澄清。
        assertEquals("需要更多信息才能继续处理该请求。", result.responseText)

        val debugEvent = listener.ofType(AgentEvent.DebugInfo::class.java).lastOrNull()
        assertNotNull(debugEvent)
        val debug = (debugEvent as AgentEvent.DebugInfo).debug

        // 解析结果保留模型返回的缺参字段（temperature/mode）。
        assertNotNull(debug.parsed)
        assertTrue(debug.parsed!!.missingArguments.contains("temperature"))
        assertTrue(debug.parsed!!.missingArguments.contains("mode"))
        assertEquals("climate.power.set", debug.parsed!!.intents.first().toolId)

        // 执行路径可观测：完整 LLM 原始响应已保留。
        assertEquals(raw, debug.rawModelContent)
    }
}
