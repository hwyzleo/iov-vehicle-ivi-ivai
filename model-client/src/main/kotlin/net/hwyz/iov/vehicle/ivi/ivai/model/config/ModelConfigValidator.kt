package net.hwyz.iov.vehicle.ivi.ivai.model.config

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Result of validating a [ModelConfigDraft]. */
sealed interface ValidationResult {
    data object Valid : ValidationResult

    data class FieldError(val field: String, val code: String, val message: String)

    data class Invalid(val errors: List<FieldError>) : ValidationResult
}

/**
 * URL + required-field validation (IVI-IVAI-DSN-CR-003):
 *  - only http/https schemes; file/content/javascript etc. are rejected (SSRF guard)
 *  - the URL is normalized (trailing slash, duplicate slashes) before saving
 *  - production builds can require https via [allowInsecureHttp] = false
 */
class ModelConfigValidator(
    private val allowInsecureHttp: Boolean = true
) {

    fun validate(draft: ModelConfigDraft): ValidationResult {
        val raw = draft.baseUrl.trim()
        if (raw.isEmpty()) {
            return ValidationResult.Invalid(
                listOf(
                    ValidationResult.FieldError(
                        field = "baseUrl",
                        code = ModelConfigErrorCode.MISSING_REQUIRED,
                        message = "模型请求地址不能为空"
                    )
                )
            )
        }

        val url = raw.toHttpUrlOrNull()
        if (url == null) {
            return urlError("模型请求地址格式非法")
        }

        val scheme = url.scheme.lowercase()
        if (scheme != "http" && scheme != "https") {
            return urlError("仅支持 http/https 协议，当前为 $scheme")
        }
        if (!allowInsecureHttp && scheme != "https") {
            return urlError("当前构建要求使用 https")
        }
        if (url.host.isBlank()) {
            return urlError("缺少主机名")
        }
        // OkHttp 会宽容地把空 authority 的 "http:///path" 重新解析为 host="path"，
        // 这里显式拒绝（SSRF / 输入错误防护）。
        if (raw.substringAfter("://").startsWith("/")) {
            return urlError("缺少主机名")
        }

        return ValidationResult.Valid
    }

    /**
     * Normalizes a valid URL string: strips trailing slash and duplicate path
     * slashes so later path concatenation can never produce `//` or split paths.
     */
    fun normalize(raw: String): String {
        val url = raw.trim().toHttpUrlOrNull() ?: return raw.trim()
        val builder = url.newBuilder()
        val segments = url.pathSegments
        var i = segments.size - 1
        while (i >= 0) {
            if (segments[i].isEmpty()) builder.removePathSegment(i)
            i--
        }
        return builder.build().toString()
    }

    private fun urlError(message: String): ValidationResult.Invalid =
        ValidationResult.Invalid(
            listOf(
                ValidationResult.FieldError(
                    field = "baseUrl",
                    code = ModelConfigErrorCode.INVALID_URL,
                    message = message
                )
            )
        )
}
