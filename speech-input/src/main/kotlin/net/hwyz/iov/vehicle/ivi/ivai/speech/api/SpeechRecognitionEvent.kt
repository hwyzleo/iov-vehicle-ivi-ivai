package net.hwyz.iov.vehicle.ivi.ivai.speech.api

/**
 * Recognition events surfaced to the controller (IVI-IVAI-DSN-CR-006).
 *
 * - [Ready] / [Listening] drive the PREPARING → LISTENING transition.
 * - [PartialResult] is a temporary preview only — it must never trigger the
 *   Agent or a Tool.
 * - [FinalResult] is the single event allowed to produce one user message.
 * - [Error] maps a platform error to a stable IVAI-ASR-xxx code.
 */
sealed interface SpeechRecognitionEvent {
    data object Ready : SpeechRecognitionEvent
    data object Listening : SpeechRecognitionEvent
    data class PartialResult(val text: String) : SpeechRecognitionEvent
    data class FinalResult(val text: String) : SpeechRecognitionEvent
    data class Error(val error: SpeechRecognitionError) : SpeechRecognitionEvent
}
