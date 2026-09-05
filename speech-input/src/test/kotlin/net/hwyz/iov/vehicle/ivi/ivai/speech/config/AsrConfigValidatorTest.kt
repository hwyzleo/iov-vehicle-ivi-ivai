package net.hwyz.iov.vehicle.ivi.ivai.speech.config

import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ASR draft validation (IVI-IVAI-DSN-CR-006): Android providers need no remote
 * fields; HTTP/VENDOR require a legal http(s) address + model; timeouts must be
 * positive and the recognition timeout must exceed the connect timeout.
 */
class AsrConfigValidatorTest {

    private val validator = AsrConfigValidator()

    @Test
    fun `android on device provider needs no remote fields`() {
        val result = validator.validate(
            AsrConfigDraft(providerType = AsrProviderType.ANDROID_ON_DEVICE)
        )
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun `android system provider with defaults is valid`() {
        val result = validator.validate(
            AsrConfigDraft(providerType = AsrProviderType.ANDROID_SYSTEM)
        )
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun `http compatible requires address and model`() {
        val result = validator.validate(
            AsrConfigDraft(
                providerType = AsrProviderType.HTTP_COMPATIBLE,
                baseUrl = "https://asr.example.com/v1"
            )
        ) as ValidationResult.Invalid

        assertTrue(result.errors.any { it.field == "modelName" })
    }

    @Test
    fun `remote provider with address and model is valid`() {
        val result = validator.validate(
            AsrConfigDraft(
                providerType = AsrProviderType.HTTP_COMPATIBLE,
                baseUrl = "https://asr.example.com/v1",
                modelName = "whisper"
            )
        )
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun `remote provider with blank address is invalid`() {
        val result = validator.validate(
            AsrConfigDraft(
                providerType = AsrProviderType.VENDOR,
                baseUrl = "   ",
                modelName = "vendor-model"
            )
        ) as ValidationResult.Invalid

        assertTrue(result.errors.any { it.field == "baseUrl" })
        assertEquals(AsrConfigErrorCode.INVALID_ENDPOINT, result.errors.first().code)
    }

    @Test
    fun `non http scheme is rejected`() {
        val result = validator.validate(
            AsrConfigDraft(
                providerType = AsrProviderType.HTTP_COMPATIBLE,
                baseUrl = "ftp://asr.example.com",
                modelName = "whisper"
            )
        ) as ValidationResult.Invalid

        assertTrue(result.errors.any { it.field == "baseUrl" })
    }

    @Test
    fun `zero timeout is rejected`() {
        val result = validator.validate(
            AsrConfigDraft(
                providerType = AsrProviderType.ANDROID_ON_DEVICE,
                connectTimeoutMs = 0L,
                recognitionTimeoutMs = 0L
            )
        ) as ValidationResult.Invalid

        assertTrue(result.errors.any { it.field == "connectTimeoutMs" })
        assertTrue(result.errors.any { it.field == "recognitionTimeoutMs" })
    }

    @Test
    fun `connect timeout larger than recognition timeout is rejected`() {
        val result = validator.validate(
            AsrConfigDraft(
                providerType = AsrProviderType.ANDROID_SYSTEM,
                connectTimeoutMs = 10_000L,
                recognitionTimeoutMs = 5_000L
            )
        ) as ValidationResult.Invalid

        assertTrue(result.errors.any { it.field == "recognitionTimeoutMs" })
    }

    @Test
    fun `production build rejects insecure http`() {
        val strict = AsrConfigValidator(allowInsecureHttp = false)
        val result = strict.validate(
            AsrConfigDraft(
                providerType = AsrProviderType.HTTP_COMPATIBLE,
                baseUrl = "http://asr.example.com/v1",
                modelName = "whisper"
            )
        ) as ValidationResult.Invalid

        assertTrue(result.errors.any { it.field == "baseUrl" })
    }
}
