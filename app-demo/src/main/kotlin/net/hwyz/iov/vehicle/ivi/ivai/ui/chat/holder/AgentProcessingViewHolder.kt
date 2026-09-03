package net.hwyz.iov.vehicle.ivi.ivai.ui.chat.holder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.ui.chat.ChatMessage

/**
 * Left-aligned agent processing bubble with a lightweight progress indicator.
 */
class AgentProcessingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    private val textView: TextView = itemView.findViewById(R.id.processingText)

    fun bind(message: ChatMessage) {
        textView.text = message.text
    }

    companion object {
        fun create(parent: ViewGroup): AgentProcessingViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_agent_processing, parent, false)
            return AgentProcessingViewHolder(view)
        }
    }
}
