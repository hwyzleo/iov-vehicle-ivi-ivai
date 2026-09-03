package net.hwyz.iov.vehicle.ivi.ivai.ui.chat.holder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.ui.chat.ChatMessage
import net.hwyz.iov.vehicle.ivi.ivai.ui.chat.DetailsBinder

/**
 * Left-aligned agent text / follow-up question bubble, with an optional
 * collapsible debug detail panel.
 */
class AgentTextViewHolder(
    itemView: View,
    private val onToggleDetail: (String) -> Unit
) : RecyclerView.ViewHolder(itemView) {

    private val textView: TextView = itemView.findViewById(R.id.agentText)
    private val detailsContainer: View? = itemView.findViewById(R.id.detailsContainer)

    fun bind(message: ChatMessage, detailsExpanded: Boolean) {
        textView.text = message.text
        DetailsBinder.bind(detailsContainer, message, detailsExpanded, onToggleDetail)
    }

    companion object {
        fun create(parent: ViewGroup, onToggleDetail: (String) -> Unit): AgentTextViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_agent_text, parent, false)
            return AgentTextViewHolder(view, onToggleDetail)
        }
    }
}
