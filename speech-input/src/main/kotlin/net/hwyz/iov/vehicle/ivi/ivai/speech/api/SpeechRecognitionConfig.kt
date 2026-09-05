package net.hwyz.iov.vehicle.ivi.ivai.speech.api

/**
 * Per-session recognition options (IVI-IVAI-DSN-CR-006).
 *
 * [preferOffline] is only a preference (EXTRA_PREFER_OFFLINE) — it is NOT a
 * guarantee that the recognizer runs offline. Offline-ness is decided by the
 * detected [SpeechCapability] / the selected provider, never inferred from this
 * flag.
 */
data class SpeechRecognitionConfig(
    val languageTag: String = "zh-CN",
    val partialResults: Boolean = true,
    val preferOffline: Boolean = true,
    val sessionId: String
)
