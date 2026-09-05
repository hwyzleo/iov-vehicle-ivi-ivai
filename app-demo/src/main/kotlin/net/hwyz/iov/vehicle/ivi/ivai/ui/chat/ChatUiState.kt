package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability

/**
 * UI state projection of the conversation (IVI-IVAI-DSN-CR-002).
 */
data class ChatUiState(
    val sessionId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val activeTurnId: String? = null,
    val isAgentBusy: Boolean = false,
    val pendingConfirmationId: String? = null,
    /** CR-006: detected speech capability for enabling the voice entry. */
    val voiceCapability: SpeechCapability = SpeechCapability.UNAVAILABLE
)
