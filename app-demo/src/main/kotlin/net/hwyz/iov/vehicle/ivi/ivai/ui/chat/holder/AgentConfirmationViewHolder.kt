package net.hwyz.iov.vehicle.ivi.ivai.ui.chat.holder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.ui.chat.ChatMessage
import net.hwyz.iov.vehicle.ivi.ivai.ui.chat.ChatMessageStatus

/**
 * Left-aligned confirmation card with 确认执行 / 取消 buttons.
 * Buttons are only enabled while the message is still waiting for the user;
 * once confirmed / cancelled / expired they are disabled (idempotency).
 */
class AgentConfirmationViewHolder(
    itemView: View,
    private val onConfirm: (String) -> Unit,
    private val onCancel: (String) -> Unit
) : RecyclerView.ViewHolder(itemView) {

    private val textView: TextView = itemView.findViewById(R.id.confirmationText)
    private val tierBadge: TextView? = itemView.findViewById(R.id.tierBadge)
    private val confirmButton: Button = itemView.findViewById(R.id.confirmButton)
    private val cancelButton: Button = itemView.findViewById(R.id.cancelButton)

    fun bind(message: ChatMessage) {
        val confirmation = message.confirmation ?: return
        textView.text = confirmation.text.ifBlank { message.text }
        AgentTextViewHolder.bindTierBadge(tierBadge, message)

        val enabled = message.status == ChatMessageStatus.WAITING_USER
        confirmButton.isEnabled = enabled
        cancelButton.isEnabled = enabled
        confirmButton.setOnClickListener { onConfirm(confirmation.confirmationId) }
        cancelButton.setOnClickListener { onCancel(confirmation.confirmationId) }
    }

    companion object {
        fun create(parent: ViewGroup, onConfirm: (String) -> Unit, onCancel: (String) -> Unit): AgentConfirmationViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_agent_confirmation, parent, false)
            return AgentConfirmationViewHolder(view, onConfirm, onCancel)
        }
    }
}
