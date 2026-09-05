package net.hwyz.iov.vehicle.ivi.ivai.speech.config

import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * ASR draft validation (IVI-IVAI-DSN-CR-006):
 *  - Android providers need no address / model / key — only timeouts + policy.
 *  - HTTP_COMPATIBLE / VENDOR require a legal http(s) address and a model name.
 *  - timeouts must be positive; recognition timeout must be > connect timeout.
 *  - production builds can require https via [allowInsecureHttp] = false.
 */
class AsrConfigValidator(
    private val allowInsecureHttp: Boolean = true
) {

    fun validate(draft: AsrConfigDraft): ValidationResult {
        val errors = mutableListOf<ValidationResult.FieldError>()

        val needsRemote =
            draft.providerType == AsrProviderType.HTTP_COMPATIBLE ||
                draft.providerType == AsrProviderType.VENDOR

        if (needsRemote) {
            validateBaseUrl(draft.baseUrl)?.let { errors += it }
            if (draft.modelName.isBlank()) {
                errors += ValidationResult.FieldError(
                    field = "modelName",
                    code = AsrConfigErrorCode.INVALID_ENDPOINT,
                    message = "远程 ASR Provider 需要填写模型名称"
                )
            }
        } else if (draft.providerType !in SUPPORTED_PROVIDERS) {
            errors += ValidationResult.FieldError(
                field = "providerType",
                code = AsrConfigErrorCode.UNSUPPORTED_PROVIDER,
                message = "不支持的 ASR Provider 类型"
            )
        }

        validateTimeout("connectTimeoutMs", draft.connectTimeoutMs)?.let { errors += it }
        validateTimeout("recognitionTimeoutMs", draft.recognitionTimeoutMs)?.let { errors += it }
        if (draft.connectTimeoutMs > draft.recognitionTimeoutMs) {
            errors += ValidationResult.FieldError(
                field = "recognitionTimeoutMs",
                code = AsrConfigErrorCode.INVALID_ENDPOINT,
                message = "识别超时需大于连接超时"
            )
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun validateTimeout(field: String, value: Long): ValidationResult.FieldError? =
        if (value <= 0) {
            ValidationResult.FieldError(
                field = field,
                code = AsrConfigErrorCode.INVALID_ENDPOINT,
                message = "超时必须为正数（毫秒）"
            )
        } else {
            null
        }

    private fun validateBaseUrl(raw: String): ValidationResult.FieldError? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.FieldError(
                field = "baseUrl",
                code = AsrConfigErrorCode.INVALID_ENDPOINT,
                message = "ASR 服务地址不能为空"
            )
        }
        val url = trimmed.toHttpUrlOrNull()
        if (url == null) {
            return urlError("ASR 服务地址格式非法")
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
        if (trimmed.substringAfter("://").startsWith("/")) {
            return urlError("缺少主机名")
        }
        return null
    }

    /** Normalizes a valid URL string (strip trailing slash + duplicate slashes). */
    fun normalizeBaseUrl(raw: String): String {
        val url = raw.trim().toHttpUrlOrNull() ?: return raw.trim()
        val builder = url.newBuilder()
        var i = url.pathSegments.size - 1
        while (i >= 0) {
            if (url.pathSegments[i].isEmpty()) builder.removePathSegment(i)
            i--
        }
        return builder.build().toString()
    }

    private fun urlError(message: String): ValidationResult.FieldError =
        ValidationResult.FieldError(
            field = "baseUrl",
            code = AsrConfigErrorCode.INVALID_ENDPOINT,
            message = message
        )

    private companion object {
        val SUPPORTED_PROVIDERS = setOf(
            AsrProviderType.ANDROID_ON_DEVICE,
            AsrProviderType.ANDROID_SYSTEM,
            AsrProviderType.HTTP_COMPATIBLE,
            AsrProviderType.VENDOR
        )
    }
}
