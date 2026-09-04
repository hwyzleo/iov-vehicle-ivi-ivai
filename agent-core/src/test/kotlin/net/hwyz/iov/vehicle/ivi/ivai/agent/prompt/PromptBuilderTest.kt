package net.hwyz.iov.vehicle.ivi.ivai.agent.prompt

import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentInput
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptBuilderTest {

    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
    private val builder = PromptBuilder(registry)

    @Test
    fun `build includes system few-shot history and user message`() {
        val session = Session()
        session.appendUser("打开空调")
        session.appendAssistant("好的")
        val messages = builder.build(
            session,
            AgentInput(requestId = "req-1", text = "温度调到24度"),
            VehicleStateSnapshot(powerOn = true)
        )

        assertEquals("system", messages.first().role)
        assertEquals("user", messages.last().role)
        assertEquals("温度调到24度", messages.last().content)
        assertTrue(messages.any { it.role == "assistant" }) // few-shot assistant examples
        // system(1) + few-shot 6 pairs(12) + history(2) + user(1)
        assertEquals(16, messages.size)
    }

    @Test
    fun `system prompt lists all six candidate tools`() {
        val messages = builder.build(Session(), AgentInput("req-1", "hi"), null)
        val system = messages.first().content
        for (toolId in registry.toolIds()) {
            assertTrue(system.contains(toolId), "system prompt should contain $toolId")
        }
    }

    @Test
    fun `system prompt contains output schema and routing rules`() {
        val messages = builder.build(Session(), AgentInput("req-1", "hi"), null)
        val system = messages.first().content
        assertTrue(system.contains("LOCAL_TOOL"))
        assertTrue(system.contains("LOCAL_DIALOGUE"))
        assertTrue(system.contains("CLOUD_AI"))
        assertTrue(system.contains("REJECT"))
        assertTrue(system.contains("我有点冷"))
        assertTrue(system.contains("候选工具"))
    }

    @Test
    fun `snapshot exposes read-only prompt template without runtime context`() {
        val snapshot = builder.snapshot()

        assertEquals("1", snapshot.promptVersion)
        assertTrue(snapshot.content.contains("候选工具"))
        assertTrue(snapshot.content.contains("输出 Schema"))
        assertTrue(snapshot.content.contains("LOCAL_TOOL"))
        for (toolId in registry.toolIds()) {
            assertTrue(snapshot.content.contains(toolId), "snapshot should list $toolId")
        }
        // 隐私边界（CR-004）：快照不含任何运行时上下文（用户输入 / 声源 / 会话 / 车辆状态）。
        // 注意：静态 System Prompt 本身也会出现「用户输入」等词，因此断言带前缀的运行时标记。
        assertTrue(!snapshot.content.contains("用户输入："))
        assertTrue(!snapshot.content.contains("声源位置："))
        assertTrue(!snapshot.content.contains("会话状态："))
        assertTrue(!snapshot.content.contains("车辆状态："))
    }
}
