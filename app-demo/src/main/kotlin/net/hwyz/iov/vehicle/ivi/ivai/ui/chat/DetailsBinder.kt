package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import android.view.View
import android.widget.TextView
import net.hwyz.iov.vehicle.ivi.ivai.demo.R

/**
 * Binds the collapsible debug detail panel attached to agent messages.
 * Hidden entirely when the message carries no debug info.
 */
object DetailsBinder {

    fun bind(
        container: View?,
        message: ChatMessage,
        expanded: Boolean,
        onToggle: (String) -> Unit
    ) {
        if (container == null) return
        val details = message.details
        if (details == null) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE

        val toggle = container.findViewById<TextView>(R.id.detailsToggle)
        val scroll = container.findViewById<View>(R.id.detailsScroll)
        val body = container.findViewById<TextView>(R.id.detailsBody)

        body.text = TurnDebugFormatter.format(details)
        toggle.text = if (expanded) "▾ 收起详情" else "▸ 查看详情"
        scroll.visibility = if (expanded) View.VISIBLE else View.GONE
        toggle.setOnClickListener { onToggle(message.messageId) }
    }
}
