package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.hwyz.iov.vehicle.ivi.ivai.ui.chat.holder.AgentConfirmationViewHolder
import net.hwyz.iov.vehicle.ivi.ivai.ui.chat.holder.AgentProcessingViewHolder
import net.hwyz.iov.vehicle.ivi.ivai.ui.chat.holder.AgentResultViewHolder
import net.hwyz.iov.vehicle.ivi.ivai.ui.chat.holder.AgentTextViewHolder
import net.hwyz.iov.vehicle.ivi.ivai.ui.chat.holder.UserTextViewHolder

/**
 * RecyclerView ListAdapter + DiffUtil for the chat message stream.
 *
 * The per-message performance-panel expansion state lives on [ChatMessage]
 * (default collapsed) and is toggled through the ViewModel, so DiffUtil's
 * content comparison (areContentsTheSame → ==) already includes it and a toggle
 * click refreshes the row (IVI-IVAI-DSN-CR-004).
 */
class ChatMessageAdapter(
    private val onConfirm: (String) -> Unit,
    private val onCancel: (String) -> Unit,
    private val onRetry: (String) -> Unit,
    private val onToggleDetail: (String) -> Unit
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(MessageDiff) {

    override fun getItemViewType(position: Int): Int =
        ChatMessageViewType.of(getItem(position))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            ChatMessageViewType.USER_TEXT -> UserTextViewHolder.create(parent)
            ChatMessageViewType.AGENT_TEXT -> AgentTextViewHolder.create(parent, onToggleDetail)
            ChatMessageViewType.AGENT_PROCESSING -> AgentProcessingViewHolder.create(parent)
            ChatMessageViewType.AGENT_CONFIRMATION ->
                AgentConfirmationViewHolder.create(parent, onConfirm, onCancel)
            ChatMessageViewType.AGENT_RESULT -> AgentResultViewHolder.create(parent, onRetry, onToggleDetail)
            else -> error("Unsupported view type: $viewType")
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        val detailsExpanded = message.isPerformanceExpanded
        when (holder) {
            is UserTextViewHolder -> holder.bind(message)
            is AgentTextViewHolder -> holder.bind(message, detailsExpanded)
            is AgentProcessingViewHolder -> holder.bind(message)
            is AgentConfirmationViewHolder -> holder.bind(message)
            is AgentResultViewHolder -> holder.bind(message, detailsExpanded)
        }
    }

    private object MessageDiff : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem.messageId == newItem.messageId

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem == newItem
    }
}
