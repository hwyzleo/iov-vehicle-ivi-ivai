package net.hwyz.iov.vehicle.ivi.ivai.speech.api

import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrRuntimeConfig

/**
 * Creates a [SpeechRecognitionEngine] from an immutable [AsrRuntimeConfig]
 * snapshot captured before ACTION_DOWN (IVI-IVAI-DSN-CR-006).
 *
 * In-flight recognition (and its controlled retry) keeps the snapshot's
 * configVersion; a new session always uses the latest saved config.
 *
 * Returns null when the requested provider is not available on this device, not
 * permitted by the fallback policy, or not yet implemented (remote providers) —
 * the caller must then refuse the voice session and keep text input working.
 */
fun interface SpeechRecognitionEngineFactory {
    fun create(config: AsrRuntimeConfig): SpeechRecognitionEngine?
}
