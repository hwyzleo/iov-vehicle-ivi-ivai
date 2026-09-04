package net.hwyz.iov.vehicle.ivi.ivai.model.config

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
}
