package net.hwyz.iov.vehicle.ivi.ivai.model.config

import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProviderType
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Result of validating a [ModelConfigDraft]. */
sealed interface ValidationResult {
    data object Valid : ValidationResult

    data class FieldError(val field: String, val code: String, val message: String)

    data class Invalid(val errors: List<FieldError>) : ValidationResult
}

/**
 * URL + required-field validation (IVI-IVAI-DSN-CR-003, provider fields CR-004):
 *  - only http/https schemes; file/content/javascript etc. are rejected (SSRF guard)
 *  - the URL is normalized (trailing slash, duplicate slashes) before saving
 *  - production builds can require https via [allowInsecureHttp] = false
 *  - OPENAI_COMPATIBLE requires a model name (IVAI-CONFIG-009) and a legal
 *    endpoint path (IVAI-CONFIG-010) that never duplicates /v1 or `//`
 */
class ModelConfigValidator(
    private val allowInsecureHttp: Boolean = true
) {

    fun validate(draft: ModelConfigDraft): ValidationResult {
        val errors = mutableListOf<ValidationResult.FieldError>()
        validateBaseUrl(draft.baseUrl)?.let { errors += it }
        if (draft.providerType == ModelProviderType.OPENAI_COMPATIBLE) {
            if (draft.modelName.isNullOrBlank()) {
                errors += ValidationResult.FieldError(
                    field = "modelName",
                    code = ModelConfigErrorCode.PROVIDER_MISMATCH,
                    message = "OpenAI 兼容 Provider 需要填写模型名"
                )
            }
            validateEndpointPath(draft.baseUrl, draft.endpointPath)?.let { errors += it }
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun validateBaseUrl(raw: String): ValidationResult.FieldError? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.FieldError(
                field = "baseUrl",
                code = ModelConfigErrorCode.MISSING_REQUIRED,
                message = "模型请求地址不能为空"
            )
        }

        val url = trimmed.toHttpUrlOrNull()
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
        if (trimmed.substringAfter("://").startsWith("/")) {
            return urlError("缺少主机名")
        }
        return null
    }

    private fun validateEndpointPath(
        baseUrl: String,
        endpointPath: String?
    ): ValidationResult.FieldError? {
        val raw = endpointPath?.trim()
        if (raw.isNullOrEmpty()) return null
        if (!raw.startsWith("/")) {
            return ValidationResult.FieldError(
                field = "endpointPath",
                code = ModelConfigErrorCode.INVALID_ENDPOINT_PATH,
                message = "Endpoint Path 必须以 / 开头"
            )
        }
        if (raw.contains("//")) {
            return ValidationResult.FieldError(
                field = "endpointPath",
                code = ModelConfigErrorCode.INVALID_ENDPOINT_PATH,
                message = "Endpoint Path 不允许出现双斜杠"
            )
        }
        if (raw.contains("?")) {
            return ValidationResult.FieldError(
                field = "endpointPath",
                code = ModelConfigErrorCode.INVALID_ENDPOINT_PATH,
                message = "Endpoint Path 不允许携带查询参数"
            )
        }
        // 禁止重复 /v1：Base URL 已含 /v1 时，Endpoint Path 不能再以 /v1 开头。
        val basePathHasV1 = baseUrl.trim().toHttpUrlOrNull()?.pathSegments?.firstOrNull() == "v1"
        if (basePathHasV1 && raw.split("/").firstOrNull { it.isNotBlank() } == "v1") {
            return ValidationResult.FieldError(
                field = "endpointPath",
                code = ModelConfigErrorCode.INVALID_ENDPOINT_PATH,
                message = "Endpoint Path 与 Base URL 的 /v1 重复"
            )
        }
        return null
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

    /**
     * Joins a normalized base URL and an endpoint path without duplicate slashes
     * or a duplicated leading /v1 segment (CR-004).
     */
    fun joinEndpointPath(baseUrl: String, endpointPath: String?): String {
        val base = normalize(baseUrl).trimEnd('/')
        val path = endpointPath?.trim()?.trimStart('/')?.trimEnd('/').orEmpty()
        if (path.isEmpty()) return base
        // 若 Base URL 已带 /v1 而 Endpoint Path 又带 /v1，去掉 Endpoint Path 的 /v1。
        val baseFirstSegment = base.toHttpUrlOrNull()?.pathSegments?.firstOrNull()
        val pathSegments = path.split("/")
        val effective =
            if (baseFirstSegment == "v1" && pathSegments.firstOrNull() == "v1") {
                pathSegments.drop(1).joinToString("/")
            } else {
                path
            }
        return if (effective.isEmpty()) base else "$base/$effective"
    }

    private fun urlError(message: String): ValidationResult.FieldError =
        ValidationResult.FieldError(
            field = "baseUrl",
            code = ModelConfigErrorCode.INVALID_URL,
            message = message
        )
}
