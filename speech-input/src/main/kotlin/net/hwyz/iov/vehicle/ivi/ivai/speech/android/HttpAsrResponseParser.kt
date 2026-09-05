package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.AsrErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionError
import okhttp3.Response

/**
 * Parses an HTTP_COMPATIBLE ASR response (IVI-IVAI-DSN-CR-007, OpenAI Whisper
 * `/audio/transcriptions` style).
 *
 * Contract:
 *  - HTTP 2xx + `{"text":"..."}` → [Success] with trimmed text.
 *  - HTTP 401/403            → [Failure] IVAI-ASR-011 (remote auth).
 *  - HTTP 5xx                → [Failure] IVAI-ASR-013 (remote service error).
 *  - other non-2xx           → network/service category with the sanitized HTTP
 *    status retained for diagnostics.
 *  - 2xx with missing/wrong-type `text` or illegal JSON → IVAI-ASR-012.
 *  - 2xx with blank text                                 → IVAI-ASR-010.
 *
 * Server error bodies are only used for sanitized diagnostics; raw bodies, keys
 * and audio must never be surfaced to the UI.
 */
class HttpAsrResponseParser(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    @Serializable
    private data class TranscriptionBody(val text: String? = null)

    @Serializable
    private data class ErrorBody(val error: ErrorDetail? = null) {
        @Serializable
        data class ErrorDetail(val message: String? = null)
    }

    /** Parses [response]; the caller must close the response (use {}). */
    fun parse(response: Response): HttpAsrParseResult {
        val code = response.code
        if (code !in 200..299) {
            val status = response.code
            return HttpAsrParseResult.Failure(
                error = mapHttpError(status),
                httpStatus = status
            )
        }

        val bodyText = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        val status = response.code
        val body = runCatching { json.decodeFromString(TranscriptionBody.serializer(), bodyText) }
            .getOrNull()
            ?: return HttpAsrParseResult.Failure(
                error = speechError(
                    AsrErrorCode.REMOTE_INVALID_RESPONSE,
                    "远程 ASR 响应非法：无法解析 JSON"
                ),
                httpStatus = status
            )

        val text = body.text
        if (text == null) {
            return HttpAsrParseResult.Failure(
                error = speechError(
                    AsrErrorCode.REMOTE_INVALID_RESPONSE,
                    "远程 ASR 响应缺少 text 字段"
                ),
                httpStatus = status
            )
        }

        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return HttpAsrParseResult.Failure(
                error = speechError(AsrErrorCode.EMPTY_RESULT, "远程 ASR 最终结果为空"),
                httpStatus = status
            )
        }

        return HttpAsrParseResult.Success(text = trimmed, httpStatus = status)
    }

    private fun mapHttpError(status: Int): SpeechRecognitionError = when {
        status == 401 || status == 403 ->
            speechError(AsrErrorCode.REMOTE_UNAUTHORIZED, "远程 ASR 鉴权失败（HTTP $status）")
        status in 500..599 ->
            speechError(AsrErrorCode.REMOTE_SERVICE_ERROR, "远程 ASR 服务错误（HTTP $status）")
        status in 400..499 ->
            speechError(AsrErrorCode.NETWORK_ERROR, "远程 ASR 请求被拒绝（HTTP $status）")
        else ->
            speechError(AsrErrorCode.REMOTE_INVALID_RESPONSE, "远程 ASR 非预期响应（HTTP $status）")
    }

    private fun speechError(code: String, message: String): SpeechRecognitionError =
        SpeechRecognitionError(
            code = code,
            message = message,
            engineType = "http-compatible",
            onDevice = false
        )
}

/** Outcome of parsing a remote ASR response; [httpStatus] is the raw (sanitized) status, null for pure network failures. */
sealed interface HttpAsrParseResult {
    data class Success(val text: String, val httpStatus: Int) : HttpAsrParseResult
    data class Failure(val error: SpeechRecognitionError, val httpStatus: Int? = null) : HttpAsrParseResult
}
