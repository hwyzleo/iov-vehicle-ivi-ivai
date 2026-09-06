package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.TurnDebugInfo

/**
 * Formats the non-performance debug payload of a [TurnDebugInfo] (CR-004):
 * status / route / error code, parsed result and triggered tool.
 *
 * Privacy boundary: the System Prompt, composed message orchestration and raw
 * model output are deliberately NOT rendered here — Agent Events never expose
 * the full prompt to chat consumers; the read-only PromptInfo settings page
 * serves the prompt snapshot instead. The segmented performance lives in
 * [AgentPerformanceDetailsView].
 */
object TurnDebugFormatter {

    private val SECTION_COLOR = 0xFF1565C0.toInt()
    private val CODE_COLOR = 0xFF37474F.toInt()

    /**
     * 失败详情展示失败原因与完整原始模型返回数据（用户要求的调试手段）：
     * 解析失败时把模型返回内容全文透出，便于定位“模型返回格式异常”的具体原因。
     * 仅在失败（errorDetail 非空）时展示，正常执行不渲染原始输出。
     */
    fun format(debug: TurnDebugInfo): CharSequence {
        val sb = SpannableStringBuilder()

        section(sb, "⚡ 执行状态")
        body(sb, "状态：${debug.state ?: "-"}    路由：${debug.route ?: "-"}")
        debug.errorCode?.let { body(sb, "错误码：$it") }
        debug.errorDetail?.let { body(sb, "失败详情：\n$it") }
        if (debug.replayed) body(sb, "（命中幂等缓存，未重复执行）")
        body(sb, "requestId：${debug.requestId}")
        sb.append("\n")

        // CR-005: 分级执行路径（初始→最终 + 轨迹）
        val pathSegment = executionPathSegment(debug)
        if (pathSegment.isNotBlank()) {
            section(sb, "🧭 执行路径")
            sb.append(pathSegment)
            sb.append("\n")
        }

        // CR-009: 匹配资产（领域 / 能力包 / 工具或工作流），随 L0~L3 一起展示
        val matchedSegment = matchedAssetSegment(debug)
        if (matchedSegment.isNotBlank()) {
            section(sb, "🎯 匹配资产")
            sb.append(matchedSegment)
            sb.append("\n")
        }

        // CR-005: RAG 运行信息
        debug.rag?.let { rag ->
            section(sb, "📡 RAG")
            body(sb, "配置：${if (rag.configuredEnabled) "已打开" else "关闭"}")
            body(sb, "运行状态：${rag.runtimeStatus.name}")
            body(
                sb,
                "执行：" + when {
                    rag.retrievalExecuted -> "已使用（${rag.retrievalType ?: "-"}）"
                    !rag.configuredEnabled -> "未启用 · 固定候选"
                    else -> "不可用已降级"
                }
            )
            rag.indexVersion?.let { body(sb, "索引版本：$it") }
            rag.fallbackReason?.let { body(sb, "降级原因：$it") }
            sb.append("\n")
        }

        debug.parsed?.let { parsed ->
            section(sb, "🧠 解析结果")
            body(sb, "路由：${parsed.route}")
            body(sb, "置信度：${parsed.confidence}")
            body(sb, "风险：${parsed.riskLevel}")
            body(sb, "需要确认：${parsed.needConfirmation}")
            body(sb, "缺参：${if (parsed.missingArguments.isEmpty()) "-" else parsed.missingArguments.joinToString("、")}")
            parsed.reasonCode?.let { body(sb, "reasonCode：$it") }
            if (parsed.intents.isNotEmpty()) {
                parsed.intents.forEach { intent ->
                    val label = intent.toolName?.let { cn -> "$cn（${intent.toolId}）" } ?: intent.toolId
                    code(
                        sb,
                        "→ $label${intent.functionId?.let { " ($it)" } ?: ""}  ${intent.arguments}"
                    )
                }
            }
            sb.append("\n")
        }

        debug.tool?.let { tool ->
            section(sb, "🔧 工具执行")
            tool.toolId?.let {
                val cn = tool.toolName ?: it
                body(sb, "工具：${if (cn == it) it else "$cn（$it）"}")
            }
            tool.status?.let { body(sb, "状态：$it") }
            tool.latencyMs?.let { body(sb, "耗时：${it}ms") }
            tool.errorCode?.let { body(sb, "错误码：$it") }
            tool.message?.let { body(sb, "结果：$it") }
        }

        return sb
    }

    /** 渲染 初始 → 最终 层级与逐级迁移轨迹（L0→L1 原因 xxx）。 */
    private fun executionPathSegment(debug: TurnDebugInfo): String {
        val sb = StringBuilder()
        val initial = debug.intentTier ?: return ""
        val final = debug.finalTier ?: return ""
        sb.append("层级：").append(initial)
        if (initial != final) sb.append(" → ").append(final)
        sb.append("\n")
        if (debug.transitions.isNotEmpty()) {
            debug.transitions.forEach { t ->
                bodyPath(sb, "${t.from.name} → ${t.to.name}（${t.reasonCode}）")
            }
        }
        debug.candidateSource?.let { bodyPath(sb, "候选来源：$it") }
        // CR-010 可观测性：走过本地模型（L1/L2，候选来源为 LLM）时，把 LLM 的完整
        // 响应原文打印在执行路径里，便于核对「匹配资产 / 解析结果」与模型输出的关系
        // （例如模型误报缺参、选错 Tool 时能直接看到原文）。
        val llmResponded = debug.candidateSource == "L1_LOCAL_LLM" ||
            debug.candidateSource == "L3_CLOUD_AI"
        if (llmResponded && !debug.rawModelContent.isNullOrBlank()) {
            bodyPath(sb, "—— LLM 返回 ——\n${debug.rawModelContent}")
        }
        return sb.toString()
    }

    /**
     * CR-009: 当前指令匹配到的领域 / 能力包 / 工具或工作流（随 L0~L3 一起展示）。
     * 数据来自 AgentWorkflow 的 Cr008DebugInfo + 执行结果 ToolDebugInfo。
     */
    private fun matchedAssetSegment(debug: TurnDebugInfo): String {
        val sb = StringBuilder()
        val cr008 = debug.cr008
        if (cr008 == null) return ""

        val domainCode = cr008.domain
        if (domainCode != null) {
            bodyPath(sb, "领域：$domainCode · ${DomainCodeLabels.label(domainCode)}")
        }
        cr008.operationType?.let { bodyPath(sb, "操作类型：$it") }
        if (cr008.selectedPacks.isNotEmpty()) {
            // CR-010 展示：能力包中文名（代码）一一对应。
            val packs = cr008.selectedPacks.mapIndexed { index, packId ->
                val cn = cr008.selectedPackNames.getOrNull(index) ?: packId
                if (cn == packId) packId else "$cn（$packId）"
            }
            bodyPath(sb, "能力包：${packs.joinToString("、")}")
        }
        // 工作流优先（WF 场景编排），否则展示执行/解析出的工具。
        cr008.workflowId?.let {
            val cn = cr008.workflowName ?: it
            bodyPath(sb, "工作流：${if (cn == it) it else "$cn（$it）"}${cr008.workflowState?.let { s -> "（$s）" } ?: ""}")
        } ?: run {
            val toolId = debug.tool?.toolId ?: debug.parsed?.intents?.firstOrNull()?.toolId
            toolId?.let {
                val cn = debug.tool?.toolName ?: debug.parsed?.intents?.firstOrNull()?.toolName ?: it
                bodyPath(sb, "工具：${if (cn == it) it else "$cn（$it）"}")
            }
        }
        return sb.toString()
    }

    private fun bodyPath(sb: StringBuilder, text: String) {
        sb.append(text).append("\n")
    }

    /**
     * BD01～BD10 编码 → 中文名称（app-demo 不直接依赖 tool-registry，本地维护）。
     * 与 tool-registry 的 BusinessDomainId.label 保持一致。
     */
    internal object DomainCodeLabels {
        private val LABELS = mapOf(
            "BD01" to "座舱舒适", "BD02" to "车身控制", "BD03" to "车辆设置与驾驶",
            "BD04" to "能源与补能", "BD05" to "影像与记录", "BD06" to "导航与出行",
            "BD07" to "通讯", "BD08" to "媒体娱乐", "BD09" to "应用与系统",
            "BD10" to "信息服务"
        )
        fun label(code: String): String = LABELS[code] ?: code
    }

    private fun section(sb: SpannableStringBuilder, title: String) {
        val start = sb.length
        sb.append(title).append("\n")
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(SECTION_COLOR), start, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun body(sb: SpannableStringBuilder, text: String) {
        sb.append(text).append("\n")
    }

    private fun code(sb: SpannableStringBuilder, text: String) {
        val start = sb.length
        sb.append(text).append("\n")
        sb.setSpan(TypefaceSpan("monospace"), start, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(CODE_COLOR), start, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
