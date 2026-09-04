package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import android.view.View
import android.widget.TextView
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.model.AgentPerformanceMetrics
import net.hwyz.iov.vehicle.ivi.ivai.model.HttpNetworkMetrics
import net.hwyz.iov.vehicle.ivi.ivai.model.ProviderComputeMetrics

/**
 * Binds the collapsible segmented performance panel under an agent reply bubble
 * (IVI-IVAI-DSN-CR-004). Unavailable metrics are rendered as "不支持/未知",
 * never as 0; network / providerCompute are diagnostic sub-metrics and are
 * rendered indented under 模型调用总耗时.
 */
object AgentPerformanceDetailsView {

    private const val UNKNOWN = "不支持/未知"
    private const val INDENT = "    "

    /**
     * Binds [performance] into the panel view. Returns whether the panel should
     * be visible (false when there is nothing to show).
     */
    fun bind(panel: View, performance: AgentPerformanceMetrics?): Boolean {
        val title = panel.findViewById<TextView>(R.id.performanceTitle)
        val body = panel.findViewById<TextView>(R.id.performanceBody)
        if (performance == null) {
            panel.visibility = View.GONE
            return false
        }
        panel.visibility = View.VISIBLE
        title.text = "分段性能明细 · requestId ${performance.requestId.takeLast(8)}"
        body.text = format(performance)
        return true
    }

    private fun format(p: AgentPerformanceMetrics): String = buildString {
        appendLine("端到端总耗时：${ms(p.endToEndMs)}")
        appendLine("排队：${ms(p.queueMs)}")
        appendLine("上下文与提示词组装：${ms(p.contextAndPromptMs)}")
        appendLine("模型调用总耗时：${ms(p.modelCallTotalMs)}")
        appendStreamingHeader(p)
        p.network?.let { appendNetwork(it) }
        p.providerCompute?.let { appendProviderCompute(it) }
        appendLine("解析与 Schema：${ms(p.parseAndSchemaMs)}")
        appendLine("路由与策略：${ms(p.routeAndPolicyMs)}")
        appendLine("工具执行：${ms(p.toolExecutionMs)}")
        appendLine("事件分发：${ms(p.eventDispatchMs)}")
        appendLine("未归因（排队/序列化/分发等，非网络耗时）：${ms(p.unattributedMs)}")
    }

    /**
     * 明确区分两个容易混淆的指标（CR-004 口径修正）：
     *  - 首字延迟（TTFT）：请求发出 → 首个 token 到达，仅流式可测得
     *  - 首字节等待（TTFB）：请求写完 → HTTP 响应头到达，网络层指标
     * 非流式时首字节等待≈完整生成耗时，绝不是首字延迟。
     */
    private fun StringBuilder.appendStreamingHeader(p: AgentPerformanceMetrics) {
        when (p.streamingUsed) {
            true -> appendLine("流式：是（逐 token 输出，已采集首字延迟）")
            false -> appendLine("流式：否（完整响应一次返回，首字节等待≈完整生成）")
            else -> appendLine("流式：未知")
        }
        if (p.streamingUsed == true) {
            appendLine("首字延迟（请求→首个 token）：${ms(p.timeToFirstTokenMs)}")
        } else {
            appendLine("首字延迟：不支持/未知（非流式无法测得）")
        }
    }

    private fun StringBuilder.appendNetwork(n: HttpNetworkMetrics) {
        appendLine("$INDENT└ 网络分段（诊断子指标，不计入模型总耗时）")
        appendLine("$INDENT${INDENT}DNS：${ms(n.dnsMs)}")
        appendLine("$INDENT${INDENT}连接：${ms(n.connectMs)}")
        appendLine("$INDENT${INDENT}TLS：${ms(n.tlsMs)}")
        appendLine("$INDENT${INDENT}请求写入：${ms(n.requestWriteMs)}")
        appendLine("$INDENT${INDENT}首字节等待（响应头，网络层）：${ms(n.timeToFirstByteMs)}")
        appendLine("$INDENT${INDENT}响应读取：${ms(n.responseReadMs)}")
        appendLine(
            "$INDENT${INDENT}连接复用：${
                n.connectionReused?.let { if (it) "是（DNS/连接/TLS 无事件）" else "否" } ?: UNKNOWN
            }"
        )
    }

    private fun StringBuilder.appendProviderCompute(c: ProviderComputeMetrics) {
        appendLine("$INDENT└ 服务端计算（${c.source}，诊断字段）")
        appendLine("$INDENT${INDENT}提示评估：${ms(c.promptEvaluationMs)}${token(c.promptEvaluationTokens)}")
        appendLine("$INDENT${INDENT}生成：${ms(c.generationMs)}${token(c.generationTokens)}")
        appendLine("$INDENT${INDENT}生成速度：${tokensPerSec(c.generationTokens, c.generationMs)}")
        appendLine("$INDENT${INDENT}服务端报告总耗时：${ms(c.totalReportedMs)}")
    }

    private fun token(count: Long?): String =
        if (count == null) "" else "（$count tok）"

    private fun tokensPerSec(tokens: Long?, durationMs: Long?): String =
        if (tokens == null || durationMs == null || durationMs <= 0) {
            UNKNOWN
        } else {
            val perSec = tokens.toDouble() / (durationMs / 1000.0)
            String.format("%.1f tok/s", perSec)
        }

    private fun ms(value: Long?): String =
        if (value == null) UNKNOWN else "${value}ms"
}
