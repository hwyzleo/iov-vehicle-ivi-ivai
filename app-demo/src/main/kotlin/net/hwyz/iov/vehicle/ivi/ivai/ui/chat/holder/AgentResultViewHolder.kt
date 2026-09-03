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
import net.hwyz.iov.vehicle.ivi.ivai.ui.chat.ChatMessageType
import net.hwyz.iov.vehicle.ivi.ivai.ui.chat.DetailsBinder

/**
 * Left-aligned tool result / error bubble. Success, failure and timeout are
 * expressed with a text label AND a status color (never color alone), a retry
 * button when the failure is retryable, and a collapsible debug detail panel.
 */
class AgentResultViewHolder(
    itemView: View,
    private val onRetry: (String) -> Unit,
    private val onToggleDetail: (String) -> Unit
) : RecyclerView.ViewHolder(itemView) {

    private val card: View = itemView.findViewById(R.id.resultCard)
    private val statusView: TextView = itemView.findViewById(R.id.resultStatus)
    private val textView: TextView = itemView.findViewById(R.id.resultText)
    private val retryButton: Button = itemView.findViewById(R.id.retryButton)
    private val detailsContainer: View? = itemView.findViewById(R.id.detailsContainer)

    fun bind(message: ChatMessage, detailsExpanded: Boolean) {
        textView.text = message.text

        val isError = message.type == ChatMessageType.ERROR
        val (label, backgroundRes, colorRes) = when {
            isError -> Triple("错误", R.drawable.bg_bubble_error, R.color.status_fail)
            message.status == ChatMessageStatus.TIMEOUT -> Triple("超时", R.drawable.bg_bubble_error, R.color.status_timeout)
            message.status == ChatMessageStatus.FAILED -> Triple("失败", R.drawable.bg_bubble_error, R.color.status_fail)
            else -> Triple("成功", R.drawable.bg_bubble_result, R.color.status_ok)
        }
        statusView.text = "[$label]"
        statusView.setTextColor(itemView.context.getColor(colorRes))
        card.setBackgroundResource(backgroundRes)

        val showRetry = message.retryable && !isError
        retryButton.visibility = if (showRetry) View.VISIBLE else View.GONE
        retryButton.setOnClickListener { onRetry(message.messageId) }

        DetailsBinder.bind(detailsContainer, message, detailsExpanded, onToggleDetail)
    }

    companion object {
        fun create(
            parent: ViewGroup,
            onRetry: (String) -> Unit,
            onToggleDetail: (String) -> Unit
        ): AgentResultViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_agent_result, parent, false)
            return AgentResultViewHolder(view, onRetry, onToggleDetail)
        }
    }
}
