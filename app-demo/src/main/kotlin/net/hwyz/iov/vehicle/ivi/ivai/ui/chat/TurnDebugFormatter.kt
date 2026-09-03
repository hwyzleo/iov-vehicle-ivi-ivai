package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.TurnDebugInfo

/**
 * Formats a [TurnDebugInfo] into a structured, readable debug panel:
 * performance stats, parsed result, triggered tool, raw model output and the
 * full prompt orchestration (system prompt + composed messages).
 */
object TurnDebugFormatter {

    private val SECTION_COLOR = 0xFF1565C0.toInt()
    private val CODE_COLOR = 0xFF37474F.toInt()

    fun format(debug: TurnDebugInfo): CharSequence {
        val sb = SpannableStringBuilder()

        section(sb, "⚡ 性能统计")
        body(sb, "状态：${debug.state ?: "-"}    路由：${debug.route ?: "-"}")
        debug.errorCode?.let { body(sb, "错误码：$it") }
        body(sb, "模型耗时：${ms(debug.modelLatencyMs)}    工具耗时：${ms(debug.toolLatencyMs)}    总耗时：${ms(debug.totalLatencyMs)}")
        if (debug.replayed) body(sb, "（命中幂等缓存，未重复执行）")
        body(sb, "requestId：${debug.requestId}")
        sb.append("\n")

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
            sb.append("\n")
        }

        debug.rawModelContent?.let { raw ->
            section(sb, "📄 模型原始输出")
            code(sb, raw.trim())
            sb.append("\n")
        }

        if (debug.composedMessages.isNotEmpty()) {
            section(sb, "🧾 请求编排（发给模型的 ${debug.composedMessages.size} 条消息）")
            debug.composedMessages.firstOrNull { it.role == "system" }?.let { system ->
                body(sb, "系统提示词：")
                code(sb, system.content.trim())
                sb.append("\n")
            }
            debug.composedMessages.forEach { msg ->
                val content = if (msg.content.length > 600) msg.content.take(600) + "…" else msg.content
                code(sb, "[${msg.role}] $content")
            }
        }

        return sb
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

    private fun ms(value: Long?): String =
        if (value == null || value < 0) "-" else "${value}ms"
}
