package net.hwyz.iov.vehicle.ivi.ivai.speech.api

/**
 * ASR error (IVI-IVAI-DSN-CR-006 error map IVAI-ASR-001..010).
 *
 * [code] is the stable IVAI-ASR-xxx code; engine type / on-device flags are
 * carried for safe diagnostics. Raw audio, API keys and full recognition text
 * must never be logged through this object.
 */
data class SpeechRecognitionError(
    val code: String,
    val message: String,
    val engineType: String? = null,
    val onDevice: Boolean? = null,
    val cause: Throwable? = null
)

/** IVAI-ASR-* error codes (IVI-IVAI-DSN-CR-006 + IVI-IVAI-DSN-CR-007). */
object AsrErrorCode {
    /** Mic permission not granted. */
    const val PERMISSION_DENIED = "IVAI-ASR-001"

    /** No usable Recognition Service on this device. */
    const val NO_RECOGNITION_SERVICE = "IVAI-ASR-002"

    /** No speech detected. */
    const val NO_SPEECH = "IVAI-ASR-003"

    /** Speech could not be matched to text. */
    const val NO_MATCH = "IVAI-ASR-004"

    /** Recognition / finalizing timed out. */
    const val RECOGNITION_TIMEOUT = "IVAI-ASR-005"

    /** Recognition network error. */
    const val NETWORK_ERROR = "IVAI-ASR-006"

    /** Recognition service is busy (incl. audio buffer reaching its cap). */
    const val RECOGNIZER_BUSY = "IVAI-ASR-007"

    /** Recognizer internal error. */
    const val RECOGNIZER_INTERNAL = "IVAI-ASR-008"

    /** Duplicate or stale session callback — dropped for diagnostics. */
    const val STALE_SESSION = "IVAI-ASR-009"

    /** Final result was empty — never submitted to the Agent. */
    const val EMPTY_RESULT = "IVAI-ASR-010"

    /** Remote ASR authentication failed (HTTP 401/403). */
    const val REMOTE_UNAUTHORIZED = "IVAI-ASR-011"

    /** Remote ASR response was invalid (bad JSON / missing text / wrong type). */
    const val REMOTE_INVALID_RESPONSE = "IVAI-ASR-012"

    /** Remote ASR service error (HTTP 5xx). */
    const val REMOTE_SERVICE_ERROR = "IVAI-ASR-013"
}
