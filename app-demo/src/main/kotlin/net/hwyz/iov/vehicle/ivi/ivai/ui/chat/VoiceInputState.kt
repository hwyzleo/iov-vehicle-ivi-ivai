package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

/**
 * Push-to-talk state machine projection (IVI-IVAI-DSN-CR-006).
 *
 * Transitions are serialized inside [VoiceInputController]; at most one active
 * recognition session exists at any time. Cancelled / Failed are transient and
 * settle back to [Idle] after a short delay so the UI can surface the message.
 */
sealed interface VoiceInputState {
    data object Idle : VoiceInputState
    data object Checking : VoiceInputState
    data object Preparing : VoiceInputState
    data class Listening(val partialText: String = "") : VoiceInputState
    data object Finalizing : VoiceInputState
    data object Cancelled : VoiceInputState
    data class Failed(val error: VoiceInputError) : VoiceInputState
}

/** User-facing ASR failure (stable IVAI-ASR-xxx code + safe message). */
data class VoiceInputError(
    val code: String,
    val message: String
)
