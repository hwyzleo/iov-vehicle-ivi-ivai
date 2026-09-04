package net.hwyz.iov.vehicle.ivi.ivai.model

/**
 * Global IVAI-MODEL-* error codes produced by model-client (IVI-IVAI-DSN-CR-004).
 */
object ModelErrorCode {
    const val MODEL_UNAVAILABLE = "IVAI-MODEL-001"
    const val RESPONSE_PARSE = "IVAI-MODEL-002"
    const val INVALID_RESPONSE = "IVAI-MODEL-003"
    const val PROVIDER_UNSUPPORTED = "IVAI-MODEL-004"
}

/**
 * Normalized error categories produced by model-client. agent-core maps these to
 * the global IVAI-MODEL-* error codes.
 */
enum class ModelErrorKind {
    /** Network unreachable / DNS / connection refused / socket errors. */
    NETWORK_UNAVAILABLE,

    /** Request exceeded the configured timeout. */
    TIMEOUT,

    /** Request cancelled by the caller (coroutine cancellation). */
    REQUEST_CANCELLED,

    /** Provider returned a non-2xx HTTP status. */
    HTTP_ERROR,

    /** Outer response or message.content could not be parsed as JSON. */
    RESPONSE_PARSE_ERROR,

    /** Provider returned a 2xx response lacking valid choices/content (IVAI-MODEL-003). */
    INVALID_RESPONSE,

    /** Provider type or model configuration is unsupported (IVAI-MODEL-004). */
    PROVIDER_UNSUPPORTED,

    /**
     * Runtime configuration is missing / invalid / undecryptable, so no request
     * may be issued (IVI-IVAI-DSN-CR-003). Usually wraps [net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigException].
     */
    CONFIGURATION_ERROR
}

/**
 * Thrown by [ModelProvider] implementations for any network / HTTP / model / parse failure.
 */
class ModelClientException(
    val kind: ModelErrorKind,
    val httpCode: Int? = null,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
