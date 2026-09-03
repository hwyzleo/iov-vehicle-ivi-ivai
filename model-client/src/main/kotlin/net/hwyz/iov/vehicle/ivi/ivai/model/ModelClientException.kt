package net.hwyz.iov.vehicle.ivi.ivai.model

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

    /** Ollama returned a non-2xx HTTP status. */
    HTTP_ERROR,

    /** Outer response or message.content could not be parsed as JSON. */
    RESPONSE_PARSE_ERROR
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
