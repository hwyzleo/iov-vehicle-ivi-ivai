package net.hwyz.iov.vehicle.ivi.ivai.speech.observability

/**
 * Independent ASR input-stage metrics (IVI-IVAI-DSN-CR-006 + IVI-IVAI-DSN-CR-007).
 *
 * Recorded BEFORE the Agent request; the ASR time must never be merged into the
 * Agent's endToEndMs. After submission the generated requestId links the two,
 * but the durations are never re-added.
 *
 * [wavEncodeMs], [uploadMs], [responseReadMs] and [httpStatus] are nullable
 * diagnostics used by the HTTP-compatible engine (engineType = "http-compatible",
 * onDevice = false). They stay null when the stage is not observable and must
 * never be faked as 0. Metrics never carry audio, recognition text, API keys,
 * the Authorization header or full URL query params.
 */
data class SpeechRecognitionMetrics(
    val voiceSessionId: String,
    val engineType: String,
    val onDevice: Boolean,
    val prepareMs: Long? = null,
    val listeningMs: Long? = null,
    val wavEncodeMs: Long? = null,
    val uploadMs: Long? = null,
    val responseReadMs: Long? = null,
    val finalizingMs: Long? = null,
    val resultLength: Int? = null,
    val httpStatus: Int? = null,
    val errorCode: String? = null
)

/**
 * Sink for ASR metrics. Implementations must only log the fields above — never
 * raw audio, keys, or full recognition text beyond policy.
 */
fun interface SpeechRecognitionMetricsRecorder {
    fun record(metrics: SpeechRecognitionMetrics)
}
