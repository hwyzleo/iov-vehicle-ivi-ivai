package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability

/**
 * Abstraction between the ViewModel and the ASR runtime owned by AgentService
 * (IVI-IVAI-DSN-CR-006), mirroring [ChatAgentGateway] / [ModelConfigGateway] so
 * the ViewModel stays unit-testable and never touches SpeechRecognizer directly.
 */
interface VoiceInputGateway {

    /**
     * Creates a fresh engine from the current immutable config snapshot.
     * Returns null when the provider is unavailable / not implemented — the
     * controller then refuses the session and keeps text input working.
     */
    suspend fun createVoiceSession(): VoiceEngineSession?

    /** Device-level capability for enabling/disabling the voice entry. */
    fun capability(): SpeechCapability
}
