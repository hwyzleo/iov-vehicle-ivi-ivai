package net.hwyz.iov.vehicle.ivi.ivai.agent.prompt

import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentInput
import net.hwyz.iov.vehicle.ivi.ivai.model.ChatMessage
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeChunk
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolCandidate
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolDefinitionSummary
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
            registry.all()
                .sortedByDescending { it.selectionPriority }
                .forEach { appendLine(renderToolSummary(ToolDefinitionSummary.from(it))) }
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
        val summaries = registry.all()
            .sortedByDescending { it.selectionPriority }
            .map { ToolDefinitionSummary.from(it) }
        return buildToolSelection(session, input, vehicleState, summaries)
    }

    /**
     * L1 tool-selection prompt (CR-005): the candidate set is the recalled Top-K
     * (retrieved or fixed) instead of the full registry. The output Schema stays
     * identical so the downstream parse / validate / execute chain is unchanged.
     *
     * CR-016：候选集只注入 canonical ID、必要描述、相似 Tool 的正反例与最小参数
     * Schema，帮助模型区分易混淆 Tool（绝对档位 vs 相对步进、风口开关 vs 风向模式
     * vs 自动模式）。
     */
    fun buildWithCandidates(
        session: Session,
        input: AgentInput,
        vehicleState: VehicleStateSnapshot?,
        candidates: List<ToolCandidate>
    ): List<ChatMessage> =
        buildToolSelection(session, input, vehicleState, candidates.map { it.definition }, candidates.map { it.toolId })

    private fun buildToolSelection(
        session: Session,
        input: AgentInput,
        vehicleState: VehicleStateSnapshot?,
        toolSummaries: List<ToolDefinitionSummary>,
        candidateToolIds: List<String>? = null
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        messages += ChatMessage("system", buildSystem(session, input, vehicleState, toolSummaries, candidateToolIds))
        messages += FEW_SHOT_EXAMPLES
        messages += session.history().takeLast(MAX_HISTORY_TURNS)
        messages += ChatMessage("user", input.text)
        return messages
    }

    /**
     * L2 knowledge-answer prompt (CR-005): the local LLM answers STRICTLY from
     * the provided chunks — no tool calls, no fabrication; insufficient or
     * conflicting evidence is reported instead of guessing.
     */
    fun buildKnowledge(
        session: Session,
        input: AgentInput,
        vehicleState: VehicleStateSnapshot?,
        chunks: List<KnowledgeChunk>
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        messages += ChatMessage(
            "system",
            buildString {
                appendLine(KNOWLEDGE_SYSTEM_PROMPT)
                appendLine()
                appendLine("## 运行上下文")
                appendLine("- 用户输入：${input.text}")
                appendLine("- 车辆状态：${renderVehicleState(vehicleState)}")
                appendLine()
                appendLine("## 可用知识片段（只依据以下片段回答）")
                chunks.forEachIndexed { index, chunk ->
                    appendLine("### [${index + 1}] ${chunk.title}（${chunk.sourceId} · ${chunk.sourceVersion}）")
                    appendLine(chunk.content)
                    appendLine()
                }
            }.trim()
        )
        messages += KNOWLEDGE_FEW_SHOT_EXAMPLES
        messages += session.history().takeLast(MAX_HISTORY_TURNS)
        messages += ChatMessage("user", input.text)
        return messages
    }

    private fun buildSystem(
        session: Session,
        input: AgentInput,
        vehicleState: VehicleStateSnapshot?,
        toolSummaries: List<ToolDefinitionSummary>,
        candidateToolIds: List<String>? = null
    ): String = buildString {
        appendLine(SYSTEM_PROMPT)
        appendLine()
        appendLine("## 运行上下文")
        appendLine("- 用户输入：${input.text}")
        appendLine("- 声源位置：${input.source}")
        appendLine("- 会话状态：${session.lastRoute?.name ?: "新会话"}")
        appendLine("- 车辆状态：${renderVehicleState(vehicleState)}")
        appendLine()
        appendLine("## 候选工具（只能使用以下 toolId，不得使用集合之外的工具）")
        toolSummaries.forEach { appendLine(renderToolSummary(it)) }
        appendLine()
        appendLine("## 相似工具区分（必须严格遵守，避免选择错误工具）")
        appendLine(renderSimilarToolGuidance(candidateToolIds))
        appendLine()
        appendLine("## 输出 Schema")
        appendLine(OUTPUT_SCHEMA)
    }

    /**
     * CR-016：相似 Tool 区分指引（设计文档明确的四组易混淆候选）。
     * 只在候选包含对应 Tool 时输出，避免引入无关内容。
     */
    private fun renderSimilarToolGuidance(candidateToolIds: List<String>?): String {
        val ids = candidateToolIds?.toSet()
        val lines = mutableListOf<String>()
        if (ids == null || "climate.fan.speed.set" in ids && "climate.fan.speed.adjust" in ids) {
            lines += "- climate.fan.speed.set：设置绝对档位（如“风量调到3档”→ level=3）。"
            lines += "- climate.fan.speed.adjust：在当前值上增减步长（如“风量调大一点”→ direction=increase）。"
        }
        if (ids == null || "climate.vent.set" in ids) {
            lines += "- climate.vent.set：风口开关（如“打开前排风口”→ enabled=true）。"
        }
        if (ids == null || "climate.airflow.mode.set" in ids) {
            lines += "- climate.airflow.mode.set：吹面/吹脚/除霜等风向模式（如“吹脚”→ mode=feet）。"
        }
        if (ids == null || "climate.auto.set" in ids) {
            lines += "- climate.auto.set：自动空调模式开关（如“开启自动空调”→ enabled=true）。"
        }
        return if (lines.isEmpty()) "（无）" else lines.joinToString("\n")
    }

    private fun renderVehicleState(state: VehicleStateSnapshot?): String =
        state?.let { "空调电源：${if (it.powerOn) "开" else "关"}" } ?: "未知"

    private fun renderToolSummary(tool: ToolDefinitionSummary): String = buildString {
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

        const val KNOWLEDGE_SYSTEM_PROMPT = """你是车机车载智能助手 IVI-IVAI 的本地知识问答助手。你的唯一职责是：
根据提供的「可用知识片段」用自然语言回答用户的车辆使用/故障问题。
约束：
- 只依据提供的知识片段回答，不得使用片段之外的信息，不得编造。
- 证据不足时，明确说明“无法确认/未找到相关说明”，不得猜测。
- 片段冲突时，不得自行选择，应指出冲突并请用户转人工或云端处理。
- 不得输出任何 Tool Call、JSON 或 Markdown 代码块，直接输出纯文本回答。
- 回答可携带来源（文档标题与版本）供 UI 展示。"""

        val KNOWLEDGE_FEW_SHOT_EXAMPLES: List<ChatMessage> = listOf(
            ChatMessage("user", "胎压报警是什么意思"),
            ChatMessage(
                "assistant",
                "胎压报警指车辆检测到某个轮胎气压明显低于标准值时，仪表盘会点亮胎压报警灯。\n" +
                    "【警告】报警灯亮起时请勿继续高速行驶，应立即安全靠边停车检查轮胎。\n" +
                    "处理步骤：1. 安全停车后检查四轮外观；2. 用随车气泵补气至标准胎压；3. 若补气后仍不熄灭，请前往授权维修店检查。（来源：故障·胎压报警说明 v1.0）"
            )
        )

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
