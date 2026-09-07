package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-011 补齐 · Embedding 配置校验：全空回退合法；必填齐全且 HTTPS/白名单/范围
 * 合法通过；部分填写按缺失字段报错；Release 禁止任意 URL。
 */
class EmbeddingConfigValidatorTest {

    private val validator = EmbeddingConfigValidator()

    private fun config(
        baseUrl: String = "https://embed.example.com/v1",
        modelId: String = "embed-v3",
        dimension: Int = 768,
        timeoutMs: Long = 10_000L,
        maxRetries: Int = 2,
        batchSize: Int = 16,
        allowedHosts: List<String> = emptyList()
    ) = EmbeddingConfig(
        providerType = "HTTP_COMPATIBLE",
        baseUrl = baseUrl,
        modelId = modelId,
        dimension = dimension,
        timeoutMs = timeoutMs,
        maxRetries = maxRetries,
        batchSize = batchSize,
        allowedHosts = allowedHosts
    )

    private fun fieldNames(result: ValidationResult.Invalid): Set<String> =
        result.errors.map { it.field }.toSet()

    @Test
    fun `全空配置合法（未配置在线嵌入，运行时回退本地桩）`() {
        assertTrue(validator.validate(EmbeddingConfig()) is ValidationResult.Valid)
    }

    @Test
    fun `完整 HTTPS 配置通过`() {
        assertTrue(validator.validate(config()) is ValidationResult.Valid)
    }

    @Test
    fun `缺地址报 baseUrl 错误`() {
        val r = validator.validate(config(baseUrl = ""))
        assertTrue(r is ValidationResult.Invalid)
        assertEquals(setOf("baseUrl"), fieldNames(r as ValidationResult.Invalid))
    }

    @Test
    fun `缺模型标识报 modelId 错误`() {
        val r = validator.validate(config(modelId = "  "))
        assertTrue(r is ValidationResult.Invalid)
        assertEquals(setOf("modelId"), fieldNames(r as ValidationResult.Invalid))
    }

    @Test
    fun `维度非正报错`() {
        val r = validator.validate(config(dimension = 0))
        assertTrue(r is ValidationResult.Invalid)
        assertTrue("dimension" in fieldNames(r as ValidationResult.Invalid))
    }

    @Test
    fun `超时超出范围报错`() {
        val r = validator.validate(config(timeoutMs = 1L))
        assertTrue(r is ValidationResult.Invalid)
        assertTrue("timeoutMs" in fieldNames(r as ValidationResult.Invalid))
    }

    @Test
    fun `批量大小越界报错`() {
        val r = validator.validate(config(batchSize = 100))
        assertTrue(r is ValidationResult.Invalid)
        assertTrue("batchSize" in fieldNames(r as ValidationResult.Invalid))
    }

    @Test
    fun `重试次数越界报错`() {
        val r = validator.validate(config(maxRetries = 99))
        assertTrue(r is ValidationResult.Invalid)
        assertTrue("maxRetries" in fieldNames(r as ValidationResult.Invalid))
    }

    @Test
    fun `Release 要求 https 时 http 地址拒绝`() {
        val strict = EmbeddingConfigValidator(allowInsecureHttp = false)
        val r = strict.validate(config(baseUrl = "http://embed.example.com/v1"))
        assertTrue(r is ValidationResult.Invalid)
        assertTrue("baseUrl" in fieldNames(r as ValidationResult.Invalid))
    }

    @Test
    fun `Host 不在白名单时报 baseUrl 错误`() {
        val r = validator.validate(config(allowedHosts = listOf("allowed.example.com")))
        assertTrue(r is ValidationResult.Invalid)
        assertTrue("baseUrl" in fieldNames(r as ValidationResult.Invalid))
    }

    @Test
    fun `Host 在白名单内通过`() {
        assertTrue(validator.validate(config(allowedHosts = listOf("embed.example.com"))) is ValidationResult.Valid)
    }

    @Test
    fun `非 http 协议拒绝`() {
        val r = validator.validate(config(baseUrl = "file:///etc/passwd"))
        assertTrue(r is ValidationResult.Invalid)
        assertTrue("baseUrl" in fieldNames(r as ValidationResult.Invalid))
    }
}
