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
 * Manages the per-message detail-panel expansion state.
 */
class ChatMessageAdapter(
    private val onConfirm: (String) -> Unit,
    private val onCancel: (String) -> Unit,
    private val onRetry: (String) -> Unit
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(MessageDiff) {

    /** messageId → expanded. Messages carrying debug details auto-expand once. */
    private val expandedIds = mutableSetOf<String>()

    private val onToggleDetail: (String) -> Unit = { messageId ->
        if (!expandedIds.add(messageId)) {
            expandedIds.remove(messageId)
        }
        currentList.indexOfFirst { it.messageId == messageId }.takeIf { it >= 0 }?.let { position ->
            notifyItemChanged(position)
        }
    }

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
        if (message.details != null) {
            // Debug details are visible by default in this phase.
            expandedIds.add(message.messageId)
        }
        val detailsExpanded = message.messageId in expandedIds
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
