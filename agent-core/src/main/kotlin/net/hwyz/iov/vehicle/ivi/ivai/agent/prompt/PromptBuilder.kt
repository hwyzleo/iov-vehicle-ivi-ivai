package net.hwyz.iov.vehicle.ivi.ivai.agent.prompt

import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentInput
import net.hwyz.iov.vehicle.ivi.ivai.model.ChatMessage
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateSnapshot

/**
 * Assembles the model prompt (IVI-IVAI-DSN-CR-001):
 * 1. System Prompt (role, boundaries, routing rules, prohibitions)
 * 2. Runtime Context (user input, source, session state, vehicle state)
 * 3. Candidate Tools (first batch fixed 6 tools)
 * 4. Few-shot (explicit / implicit / missing-slot / multi-intent / open-domain / reject)
 * 5. Output Schema
 */
class PromptBuilder(private val registry: ToolRegistry) {

    /**
     * Read-only template snapshot for the settings / debug page (CR-004): the
     * static system prompt, candidate tools and output schema — no runtime context.
     */
    fun snapshot(): PromptSnapshot = PromptSnapshot(
        promptVersion = PROMPT_VERSION,
        content = buildString {
            appendLine(SYSTEM_PROMPT)
            appendLine()
            appendLine("## 候选工具（只能使用以下 toolId）")
            registry.all().sortedByDescending { it.selectionPriority }.forEach { appendLine(renderTool(it)) }
            appendLine()
            appendLine("## 输出 Schema")
            appendLine(OUTPUT_SCHEMA)
        }.trim()
    )

    fun build(
        session: Session,
        input: AgentInput,
        vehicleState: VehicleStateSnapshot?
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        messages += ChatMessage("system", buildSystem(session, input, vehicleState))
        messages += FEW_SHOT_EXAMPLES
        messages += session.history().takeLast(MAX_HISTORY_TURNS)
        messages += ChatMessage("user", input.text)
        return messages
    }

    private fun buildSystem(
        session: Session,
        input: AgentInput,
        vehicleState: VehicleStateSnapshot?
    ): String = buildString {
        appendLine(SYSTEM_PROMPT)
        appendLine()
        appendLine("## 运行上下文")
        appendLine("- 用户输入：${input.text}")
        appendLine("- 声源位置：${input.source}")
        appendLine("- 会话状态：${session.lastRoute?.name ?: "新会话"}")
        appendLine("- 车辆状态：${renderVehicleState(vehicleState)}")
        appendLine()
        appendLine("## 候选工具（只能使用以下 toolId）")
        registry.all().sortedByDescending { it.selectionPriority }.forEach { appendLine(renderTool(it)) }
        appendLine()
        appendLine("## 输出 Schema")
        appendLine(OUTPUT_SCHEMA)
    }

    private fun renderVehicleState(state: VehicleStateSnapshot?): String =
        state?.let { "空调电源：${if (it.powerOn) "开" else "关"}" } ?: "未知"

    private fun renderTool(tool: ToolDefinition): String = buildString {
        appendLine("- ${tool.toolId}${tool.functionId?.let { " (${it})" } ?: ""}：${tool.name}")
        appendLine("  说明：${tool.description}")
        appendLine("  参数：${tool.parameterSchema.replace("\n", "").replace(" ", "")}")
        appendLine("  正例：${tool.positiveExamples.joinToString("、")}")
        appendLine("  反例：${tool.negativeExamples.joinToString("、")}")
    }

    private companion object {
        const val MAX_HISTORY_TURNS = 6

        /** Bump when the prompt template changes; shown in the read-only PromptInfo page. */
        const val PROMPT_VERSION = "1"

        const val SYSTEM_PROMPT = """你是车机车载智能助手 IVI-IVAI 的车控意图解析器。你的唯一职责是：
1. 将用户输入映射到候选工具，并决定安全路由：LOCAL_TOOL / LOCAL_DIALOGUE / CLOUD_AI / REJECT。
2. 只输出一个符合「输出 Schema」的 JSON 对象，不得包含任何额外文字、解释或 Markdown 代码块。
边界：
- 只能调用候选工具中列出的 toolId。
- 你的输出只是执行候选，最终执行权在车辆端安全校验。
- 不得编造不存在的工具、参数或车辆状态。
- 动力、制动、转向等驾驶安全控制一律 REJECT。
路由规则：
- 明确车控意图且参数完整 → LOCAL_TOOL。
- 缺少必填参数需要追问 → LOCAL_DIALOGUE，并在 missingArguments 列出缺失项。
- 多意图或开放域请求 → CLOUD_AI。
- 不安全或无法处理 → REJECT。
禁止事项：
- 禁止将「我有点冷」等隐式表达无依据解释为「打开空调」。
- 禁止输出候选范围外的 toolId。
- 禁止虚构温度数值或车辆状态。"""

        const val OUTPUT_SCHEMA = """{
  "route": "LOCAL_TOOL | LOCAL_DIALOGUE | CLOUD_AI | REJECT",
  "intents": [
    {
      "toolId": "候选工具中的 toolId",
      "functionId": "兼容 Function-ID（可选）",
      "arguments": { "参数名": 参数值 }
    }
  ],
  "modelConfidence": 0.0-1.0,
  "riskLevel": "low | medium | high",
  "needConfirmation": false,
  "missingArguments": ["缺失参数名"],
  "reasonCode": "原因说明（可选）"
}"""

        val FEW_SHOT_EXAMPLES: List<ChatMessage> = listOf(
            ChatMessage("user", "打开空调"),
            ChatMessage(
                "assistant",
                """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power_on","functionId":"AC_Control_1","arguments":{"position":"driver"}}],"modelConfidence":0.98,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"EXPLICIT_INTENT"}"""
            ),
            ChatMessage("user", "我有点冷"),
            ChatMessage(
                "assistant",
                """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.temperature_increase","functionId":"AC_Temperature_2","arguments":{"position":"driver","step":1}}],"modelConfidence":0.9,"riskLevel":"medium","needConfirmation":false,"missingArguments":[],"reasonCode":"IMPLICIT_COLD_INTENT"}"""
            ),
            ChatMessage("user", "温度调到"),
            ChatMessage(
                "assistant",
                """{"route":"LOCAL_DIALOGUE","intents":[{"toolId":"climate.temperature_set","functionId":"AC_Temperature_1","arguments":{"position":"driver"}}],"modelConfidence":0.85,"riskLevel":"medium","needConfirmation":false,"missingArguments":["temperature"],"reasonCode":"MISSING_SLOT"}"""
            ),
            ChatMessage("user", "把空调打开然后把温度调到26度"),
            ChatMessage(
                "assistant",
                """{"route":"CLOUD_AI","intents":[],"modelConfidence":0.7,"riskLevel":"medium","needConfirmation":false,"missingArguments":[],"reasonCode":"MULTI_INTENT"}"""
            ),
            ChatMessage("user", "今天天气怎么样"),
            ChatMessage(
                "assistant",
                """{"route":"CLOUD_AI","intents":[],"modelConfidence":0.6,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"OPEN_DOMAIN"}"""
            ),
            ChatMessage("user", "帮我把车开走"),
            ChatMessage(
                "assistant",
                """{"route":"REJECT","intents":[],"modelConfidence":0.99,"riskLevel":"high","needConfirmation":false,"missingArguments":[],"reasonCode":"DRIVING_SAFETY"}"""
            )
        )
    }
}
