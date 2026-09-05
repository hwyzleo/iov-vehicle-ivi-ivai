package net.hwyz.iov.vehicle.ivi.ivai.speech.observability

/**
 * Independent ASR input-stage metrics (IVI-IVAI-DSN-CR-006).
 *
 * Recorded BEFORE the Agent request; the ASR time must never be merged into the
 * Agent's endToEndMs. After submission the generated requestId links the two,
 * but the durations are never re-added.
 */
data class SpeechRecognitionMetrics(
    val voiceSessionId: String,
    val engineType: String,
    val onDevice: Boolean,
    val prepareMs: Long? = null,
    val listeningMs: Long? = null,
    val finalizingMs: Long? = null,
    val resultLength: Int? = null,
    val errorCode: String? = null
)

/**
 * Sink for ASR metrics. Implementations must only log the fields above — never
 * raw audio, keys, or full recognition text beyond policy.
 */
fun interface SpeechRecognitionMetricsRecorder {
    fun record(metrics: SpeechRecognitionMetrics)
}
