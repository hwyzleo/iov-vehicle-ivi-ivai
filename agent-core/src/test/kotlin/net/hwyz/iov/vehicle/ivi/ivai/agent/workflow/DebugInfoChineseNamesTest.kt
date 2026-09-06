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
 * CR-010 展示可观测性：DebugInfo 载荷携带能力包 / 工具 / 工作流的中文名
 * （与代码 ID 一一对应），供 UI 以「中文（代码）」形式展示。
 */
class DebugInfoChineseNamesTest {

    @Test
    fun `DebugInfo 携带能力包与工具中文名`() = runTest {
        val stub = StubModelProvider(
            """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.temperature.adjust","arguments":{"direction":"increase","step":2}}],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"EXPLICIT_INTENT"}"""
        )
        val listener = CollectingAgentEventListener()
        val (workflow, _, _) = TestGraph.buildGovernedStubGraph(stub, eventListener = listener)

        workflow.process(
            AgentInput(requestId = "req-1", text = "我感觉有点冷", source = "mock", turnId = "turn-1"),
            Session()
        )

        val debugEvent = listener.ofType(AgentEvent.DebugInfo::class.java).lastOrNull()
        assertNotNull(debugEvent)
        val debug = (debugEvent as AgentEvent.DebugInfo).debug

        // 能力包：中文名与 ID 一一对应。
        val cr008 = debug.cr008
        assertNotNull(cr008)
        assertEquals(cr008!!.selectedPacks.size, cr008.selectedPackNames.size)
        assertTrue(cr008.selectedPacks.contains("cabin.climate"))
        assertTrue(cr008.selectedPackNames.contains("空调与温控"), "能力包应为中文名，实际 ${cr008.selectedPackNames}")

        // 解析结果：工具中文名 + 代码。
        assertNotNull(debug.parsed)
        val intent = debug.parsed!!.intents.first()
        assertEquals("climate.temperature.adjust", intent.toolId)
        assertEquals("调节温度", intent.toolName)

        // 工具执行：工具中文名 + 代码。
        assertNotNull(debug.tool)
        assertEquals("climate.temperature.adjust", debug.tool!!.toolId)
        assertEquals("调节温度", debug.tool!!.toolName)
    }

    @Test
    fun `Workflow 命中时携带工作流中文名`() = runTest {
        // 直接在事件上验证 cr008 工作流中文名（露营模式 → 露营模式/露营模式）。
        val stub = StubModelProvider(
            """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power.set","arguments":{"enabled":true}}],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"EXPLICIT_INTENT"}"""
        )
        val listener = CollectingAgentEventListener()
        val (workflow, _, _) = TestGraph.buildGovernedStubGraph(stub, eventListener = listener)
        workflow.process(
            AgentInput(requestId = "req-2", text = "打开空调", source = "mock", turnId = "turn-2"),
            Session()
        )
        val debug = (listener.ofType(AgentEvent.DebugInfo::class.java).lastOrNull() as AgentEvent.DebugInfo).debug
        assertNotNull(debug.tool)
        assertEquals("设置空调电源", debug.tool!!.toolName)
        assertEquals("climate.power.set", debug.tool!!.toolId)
    }
}
