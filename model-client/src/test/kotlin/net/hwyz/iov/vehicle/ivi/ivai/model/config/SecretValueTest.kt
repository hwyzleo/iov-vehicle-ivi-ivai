package net.hwyz.iov.vehicle.ivi.ivai.model.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * SecretValue must never leak the raw secret through toString(), logs or state
 * (IVAI-REQ-027 / IVI-IVAI-DSN-CR-003 security section).
 */
class SecretValueTest {

    @Test
    fun `toString always returns mask`() {
        val secret = SecretValue.of("super-secret-api-key-123")
        assertEquals(SecretValue.MASK, secret.toString())
        assertFalse(secret.toString().contains("super-secret"))
    }

    @Test
    fun `use exposes raw value only inside the block`() {
        val secret = SecretValue.of("raw-key")
        var captured: String? = null
        secret.use { captured = it }
        assertEquals("raw-key", captured)
    }

    @Test
    fun `runtime config toString does not contain the raw key`() {
        val secret = SecretValue.of("raw-key")
        val config = ModelRuntimeConfig(
            baseUrl = "http://host:11434".toHttpUrl(),
            apiKey = secret,
            version = 1L
        )
        val text = config.toString()
        assertTrue(text.contains(SecretValue.MASK))
        assertFalse(text.contains("raw-key"))
    }

    @Test
    fun `two secrets with different raws are not equal by string representation`() {
        assertTrue(SecretValue.of("a") != SecretValue.of("b"))
        assertEquals(SecretValue.of("a").toString(), SecretValue.of("b").toString())
    }
}
