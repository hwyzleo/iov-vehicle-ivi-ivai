package net.hwyz.iov.vehicle.ivi.ivai.ui.chat.holder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.ui.chat.ChatMessage

/**
 * Right-aligned user text bubble.
 */
class UserTextViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    private val textView: TextView = itemView.findViewById(R.id.userText)

    fun bind(message: ChatMessage) {
        textView.text = message.text
    }

    companion object {
        fun create(parent: ViewGroup): UserTextViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_user_text, parent, false)
            return UserTextViewHolder(view)
        }
    }
}
