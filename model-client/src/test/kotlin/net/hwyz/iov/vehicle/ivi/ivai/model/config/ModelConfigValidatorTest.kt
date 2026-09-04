package net.hwyz.iov.vehicle.ivi.ivai.model.config

import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProviderType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelConfigValidatorTest {

    private val validator = ModelConfigValidator()

    @Test
    fun `accepts valid http and https urls`() {
        assertInstanceOf(
            ValidationResult.Valid::class.java,
            validator.validate(ModelConfigDraft("http://192.168.2.170:11434"))
        )
        assertInstanceOf(
            ValidationResult.Valid::class.java,
            validator.validate(ModelConfigDraft("https://api.example.com/v1"))
        )
        assertInstanceOf(
            ValidationResult.Valid::class.java,
            validator.validate(ModelConfigDraft("  http://localhost:11434  "))
        )
    }

    @Test
    fun `rejects blank base url as missing required`() {
        val result = validator.validate(ModelConfigDraft("   "))
        val invalid = assertInstanceOf(ValidationResult.Invalid::class.java, result)
        assertEquals(ModelConfigErrorCode.MISSING_REQUIRED, invalid.errors.first().code)
    }

    @Test
    fun `rejects non-http protocols`() {
        for (url in listOf("ftp://host:21", "file:///etc/passwd", "content://x", "javascript:alert(1)", "data:text/plain,x")) {
            val result = validator.validate(ModelConfigDraft(url))
            val invalid = assertInstanceOf(ValidationResult.Invalid::class.java, result)
            assertEquals(ModelConfigErrorCode.INVALID_URL, invalid.errors.first().code)
        }
    }

    @Test
    fun `rejects malformed url and missing host`() {
        for (url in listOf("not-a-url", "http://", "http:///path")) {
            val result = validator.validate(ModelConfigDraft(url))
            val invalid = assertInstanceOf(ValidationResult.Invalid::class.java, result)
            assertEquals(ModelConfigErrorCode.INVALID_URL, invalid.errors.first().code)
        }
    }

    @Test
    fun `prod build requires https when insecure http disabled`() {
        val strict = ModelConfigValidator(allowInsecureHttp = false)
        assertInstanceOf(ValidationResult.Valid::class.java, strict.validate(ModelConfigDraft("https://api.example.com")))
        val result = strict.validate(ModelConfigDraft("http://192.168.2.170:11434"))
        val invalid = assertInstanceOf(ValidationResult.Invalid::class.java, result)
        assertEquals(ModelConfigErrorCode.INVALID_URL, invalid.errors.first().code)
    }

    @Test
    fun `normalization strips trailing slash and duplicate slashes`() {
        assertEquals("http://host:11434/", validator.normalize("http://host:11434"))
        assertEquals("http://host:11434/", validator.normalize("http://host:11434/"))
        assertEquals("http://host:11434/api", validator.normalize("http://host:11434//api//"))
        assertEquals("http://host:11434/v1", validator.normalize("  http://host:11434/v1/  "))
    }

    @Test
    fun `openai compatible requires model name with IVAI-CONFIG-009`() {
        val result = validator.validate(
            ModelConfigDraft(
                baseUrl = "http://host:8000",
                providerType = ModelProviderType.OPENAI_COMPATIBLE,
                modelName = null
            )
        )
        val invalid = assertInstanceOf(ValidationResult.Invalid::class.java, result)
        assertEquals(ModelConfigErrorCode.PROVIDER_MISMATCH, invalid.errors.first().code)
        assertEquals("modelName", invalid.errors.first().field)
    }

    @Test
    fun `openai compatible with model name and legal endpoint passes`() {
        assertInstanceOf(
            ValidationResult.Valid::class.java,
            validator.validate(
                ModelConfigDraft(
                    baseUrl = "http://host:8000",
                    providerType = ModelProviderType.OPENAI_COMPATIBLE,
                    modelName = "qwen3.5:4b",
                    endpointPath = "/v1/chat/completions"
                )
            )
        )
    }

    @Test
    fun `endpoint path must start with slash - IVAI-CONFIG-010`() {
        val result = validator.validate(
            ModelConfigDraft(
                baseUrl = "http://host:8000",
                providerType = ModelProviderType.OPENAI_COMPATIBLE,
                modelName = "m",
                endpointPath = "v1/chat"
            )
        )
        val invalid = assertInstanceOf(ValidationResult.Invalid::class.java, result)
        assertEquals(ModelConfigErrorCode.INVALID_ENDPOINT_PATH, invalid.errors.first().code)
    }

    @Test
    fun `endpoint path with double slash or query is rejected - IVAI-CONFIG-010`() {
        for (path in listOf("/a//b", "/a?x=1")) {
            val result = validator.validate(
                ModelConfigDraft(
                    baseUrl = "http://host:8000",
                    providerType = ModelProviderType.OPENAI_COMPATIBLE,
                    modelName = "m",
                    endpointPath = path
                )
            )
            val invalid = assertInstanceOf(ValidationResult.Invalid::class.java, result)
            assertEquals(ModelConfigErrorCode.INVALID_ENDPOINT_PATH, invalid.errors.first().code)
        }
    }

    @Test
    fun `endpoint path duplicating base url v1 is rejected - IVAI-CONFIG-010`() {
        val result = validator.validate(
            ModelConfigDraft(
                baseUrl = "http://host:8000/v1",
                providerType = ModelProviderType.OPENAI_COMPATIBLE,
                modelName = "m",
                endpointPath = "/v1/chat/completions"
            )
        )
        val invalid = assertInstanceOf(ValidationResult.Invalid::class.java, result)
        assertEquals(ModelConfigErrorCode.INVALID_ENDPOINT_PATH, invalid.errors.first().code)
    }

    @Test
    fun `join endpoint path merges base url and path without duplicates`() {
        assertEquals("http://host:8000/v1/chat/completions", validator.joinEndpointPath("http://host:8000", "/v1/chat/completions"))
        // Base URL already has /v1 → the endpoint's leading /v1 is dropped.
        assertEquals("http://host:8000/v1/chat/completions", validator.joinEndpointPath("http://host:8000/v1", "/v1/chat/completions"))
        assertEquals("http://host:8000", validator.joinEndpointPath("http://host:8000", null))
    }
}
