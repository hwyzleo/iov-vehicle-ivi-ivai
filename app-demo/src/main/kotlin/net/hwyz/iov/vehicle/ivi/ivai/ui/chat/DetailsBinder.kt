package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import android.view.View
import android.widget.TextView
import net.hwyz.iov.vehicle.ivi.ivai.demo.R

/**
 * Binds the collapsible detail area attached to agent messages (CR-004):
 *  - a summary line "总耗时 X ms · 查看详情" (collapsed) / "收起详情" (expanded)
 *  - the expanded panel shows the segmented performance metrics
 *    ([AgentPerformanceDetailsView]) plus structured debug info
 *    ([TurnDebugFormatter]) — never the System Prompt
 * Hidden entirely when the message carries neither performance nor debug info.
 */
object DetailsBinder {

    fun bind(
        container: View?,
        message: ChatMessage,
        expanded: Boolean,
        onToggle: (String) -> Unit
    ) {
        if (container == null) return
        val performance = message.performance
        val details = message.details
        if (performance == null && details == null) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE

        val toggle = container.findViewById<TextView>(R.id.detailsToggle)
        val scroll = container.findViewById<View>(R.id.detailsScroll)
        val performancePanel = container.findViewById<View>(R.id.performancePanel)
        val body = container.findViewById<TextView>(R.id.detailsBody)

        // Performance panel: visible when there are metrics (regardless of expansion
        // the view itself decides; expansion only toggles the scroll region).
        val hasPerformance = AgentPerformanceDetailsView.bind(performancePanel, performance)

        body.text = details?.let { TurnDebugFormatter.format(it) } ?: ""

        val collapsedLabel = if (hasPerformance && performance != null) {
            "总耗时 ${performance.endToEndMs} ms · 查看详情"
        } else {
            "查看详情"
        }
        toggle.text = if (expanded) "收起详情" else collapsedLabel
        scroll.visibility = if (expanded) View.VISIBLE else View.GONE
        toggle.setOnClickListener { onToggle(message.messageId) }
    }
}
