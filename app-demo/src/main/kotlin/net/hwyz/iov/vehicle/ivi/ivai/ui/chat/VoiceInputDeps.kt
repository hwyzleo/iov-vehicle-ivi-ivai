package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEngine
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability

/**
 * A freshly created engine plus the immutable config snapshot that created it
 * (IVI-IVAI-DSN-CR-006). Captured before ACTION_DOWN; the in-flight session
 * keeps this snapshot's configVersion even if the user saves a new config.
 */
data class VoiceEngineSession(
    val engine: SpeechRecognitionEngine,
    val languageTag: String = "zh-CN",
    val preferOffline: Boolean = true,
    val recognitionTimeoutMs: Long = 30_000L
)

/**
 * Dependencies injected into [VoiceInputController] so the state machine is
 * pure Kotlin and unit-testable (IVI-IVAI-DSN-CR-006).
 */
interface VoiceInputDeps {

    /** Creates a fresh engine from the current config snapshot; null when unavailable. */
    suspend fun createEngine(): VoiceEngineSession?

    /**
     * Submits the recognized text as HandleText(VOICE_ASR) and appends the user
     * message. Returns false when the service rejected it (busy / not connected
     * / pending confirmation) — the controller then silently recovers to Idle.
     */
    suspend fun submitText(voiceSessionId: String, text: String): Boolean
}
