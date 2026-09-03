package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

/**
 * Maps a [ChatMessage] to a RecyclerView view type id.
 */
object ChatMessageViewType {
    const val USER_TEXT = 1
    const val AGENT_TEXT = 2
    const val AGENT_PROCESSING = 3
    const val AGENT_CONFIRMATION = 4
    const val AGENT_RESULT = 5

    fun of(message: ChatMessage): Int = when (message.type) {
        ChatMessageType.TEXT ->
            if (message.role == ChatRole.USER) USER_TEXT else AGENT_TEXT
        ChatMessageType.PROCESSING -> AGENT_PROCESSING
        ChatMessageType.CONFIRMATION -> AGENT_CONFIRMATION
        ChatMessageType.TOOL_RESULT, ChatMessageType.ERROR -> AGENT_RESULT
    }
}
