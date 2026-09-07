package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Embedding 配置校验器（CR-011 配置加载后校验：HTTPS、允许的 Host、维度、
 * 超时、批量大小和模型标识；Release 环境不得允许任意 URL 动态注入）。
 *
 * 保存语义：
 *  - 全空配置 = 合法状态，表示「未配置在线嵌入模型」，运行时回退本地哈希桩；
 *  - 一旦填写任一字段，其余必填项必须齐全且合法（baseUrl + modelId + dimension
 *    三者齐备，超时/重试/批量在范围内，allowedHosts 命中）。
 */
class EmbeddingConfigValidator(
    private val allowInsecureHttp: Boolean = true
) {

    fun validate(config: EmbeddingConfig): ValidationResult {
        val errors = mutableListOf<ValidationResult.FieldError>()

        if (isBlank(config)) return ValidationResult.Valid

        validateBaseUrl(config.baseUrl, config.allowedHosts, allowInsecureHttp)?.let { errors += it }
        if (config.modelId.isBlank()) {
            errors += field("modelId", RagConfigErrorCode.MISSING_REQUIRED, "模型标识（modelId）不能为空")
        }
        if (config.dimension <= 0) {
            errors += field("dimension", RagConfigErrorCode.MISSING_REQUIRED, "向量维度必须为正整数")
        }
        if (config.timeoutMs < MIN_TIMEOUT_MS || config.timeoutMs > MAX_TIMEOUT_MS) {
            errors += field(
                "timeoutMs", RagConfigErrorCode.MISSING_REQUIRED,
                "超时必须在 ${MIN_TIMEOUT_MS}~${MAX_TIMEOUT_MS}ms 之间"
            )
        }
        if (config.maxRetries < 0 || config.maxRetries > MAX_RETRIES) {
            errors += field(
                "maxRetries", RagConfigErrorCode.MISSING_REQUIRED,
                "重试次数必须在 0~$MAX_RETRIES 之间"
            )
        }
        if (config.batchSize < 1 || config.batchSize > MAX_BATCH_SIZE) {
            errors += field(
                "batchSize", RagConfigErrorCode.MISSING_REQUIRED,
                "批量大小必须在 1~$MAX_BATCH_SIZE 之间"
            )
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun validateBaseUrl(
        raw: String,
        allowedHosts: List<String>,
        allowInsecureHttp: Boolean
    ): ValidationResult.FieldError? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return field("baseUrl", RagConfigErrorCode.MISSING_REQUIRED, "嵌入服务地址不能为空")
        }
        val url: HttpUrl? = trimmed.toHttpUrlOrNull()
        if (url == null) {
            return field("baseUrl", RagConfigErrorCode.MISSING_REQUIRED, "嵌入服务地址格式非法")
        }
        val scheme = url.scheme.lowercase()
        if (scheme != "http" && scheme != "https") {
            return field("baseUrl", RagConfigErrorCode.MISSING_REQUIRED, "仅支持 http/https 协议，当前为 $scheme")
        }
        if (!allowInsecureHttp && scheme != "https") {
            return field("baseUrl", RagConfigErrorCode.MISSING_REQUIRED, "当前构建要求使用 https")
        }
        if (url.host.isBlank()) {
            return field("baseUrl", RagConfigErrorCode.MISSING_REQUIRED, "缺少主机名")
        }
        if (trimmed.substringAfter("://").startsWith("/")) {
            return field("baseUrl", RagConfigErrorCode.MISSING_REQUIRED, "缺少主机名")
        }
        if (allowedHosts.isNotEmpty() && url.host !in allowedHosts) {
            return field(
                "baseUrl", RagConfigErrorCode.MISSING_REQUIRED,
                "主机 ${url.host} 不在允许名单（${allowedHosts.joinToString(",")}）中"
            )
        }
        return null
    }

    companion object {
        /** 全空（未配置在线嵌入）视为合法回退状态。 */
        fun isBlank(config: EmbeddingConfig): Boolean =
            config.baseUrl.isBlank() &&
                config.modelId.isBlank() &&
                config.modelVersion.isNullOrBlank() &&
                config.dimension == 0 &&
                config.allowedHosts.isEmpty() &&
                config.credentialRef.isBlank()

        const val MIN_TIMEOUT_MS = 100L
        const val MAX_TIMEOUT_MS = 120_000L
        const val MAX_RETRIES = 10
        const val MAX_BATCH_SIZE = 64

        private fun field(name: String, code: String, message: String) =
            ValidationResult.FieldError(field = name, code = code, message = message)
    }
}
