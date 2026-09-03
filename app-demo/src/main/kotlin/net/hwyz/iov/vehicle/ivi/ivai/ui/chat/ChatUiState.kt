package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

/**
 * UI state projection of the conversation (IVI-IVAI-DSN-CR-002).
 */
data class ChatUiState(
    val sessionId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val activeTurnId: String? = null,
    val isAgentBusy: Boolean = false,
    val pendingConfirmationId: String? = null
)
