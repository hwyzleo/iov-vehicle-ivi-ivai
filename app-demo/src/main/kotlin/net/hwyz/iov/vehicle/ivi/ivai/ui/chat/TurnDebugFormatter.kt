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

    fun format(debug: TurnDebugInfo): CharSequence {
        val sb = SpannableStringBuilder()

        section(sb, "⚡ 执行状态")
        body(sb, "状态：${debug.state ?: "-"}    路由：${debug.route ?: "-"}")
        debug.errorCode?.let { body(sb, "错误码：$it") }
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
                    code(
                        sb,
                        "→ ${intent.toolId}${intent.functionId?.let { " ($it)" } ?: ""}  ${intent.arguments}"
                    )
                }
            }
            sb.append("\n")
        }

        debug.tool?.let { tool ->
            section(sb, "🔧 工具执行")
            tool.toolId?.let { body(sb, "工具：$it") }
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
        return sb.toString()
    }

    private fun bodyPath(sb: StringBuilder, text: String) {
        sb.append(text).append("\n")
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
