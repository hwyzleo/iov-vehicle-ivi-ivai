package net.hwyz.iov.vehicle.ivi.ivai.speech.config

import kotlinx.coroutines.flow.StateFlow
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretValue

/**
 * User-editable draft from the ASR config screen. The apiKey change is explicit
 * ([ApiKeyAction.Keep] preserves, [ApiKeyAction.Replace] overwrites,
 * [ApiKeyAction.Clear] removes) so a provider-only save never wipes the key.
 */
data class AsrConfigDraft(
    val providerType: AsrProviderType = AsrProviderType.ANDROID_ON_DEVICE,
    val baseUrl: String = "",
    val modelName: String = "",
    val languageTag: String = AsrPublicConfig.DEFAULT_LANGUAGE_TAG,
    val preferOffline: Boolean = true,
    val connectTimeoutMs: Long = AsrPublicConfig.DEFAULT_CONNECT_TIMEOUT_MS,
    val recognitionTimeoutMs: Long = AsrPublicConfig.DEFAULT_RECOGNITION_TIMEOUT_MS,
    val fallbackPolicy: AsrFallbackPolicy = AsrFallbackPolicy.TEXT_ONLY,
    val apiKeyAction: ApiKeyAction = ApiKeyAction.Keep
)

/**
 * Published configuration state consumed by the UI and diagnostics
 * (IVI-IVAI-DSN-CR-006).
 */
sealed interface AsrConfigState {
    data object Loading : AsrConfigState
    data class Valid(val config: AsrRuntimeConfig) : AsrConfigState

    /** Unusable (missing / corrupt / incompatible / undecryptable). */
    data class Invalid(val reason: String, val errorCode: String) : AsrConfigState
}

/** IVAI-ASR-CONFIG-* error codes (IVI-IVAI-DSN-CR-006). */
object AsrConfigErrorCode {
    const val UNSUPPORTED_PROVIDER = "IVAI-ASR-CONFIG-001"
    const val INVALID_ENDPOINT = "IVAI-ASR-CONFIG-002"
    const val KEY_ERROR = "IVAI-ASR-CONFIG-003"
    const val PERSISTENCE_FAILED = "IVAI-ASR-CONFIG-004"
    const val TEST_FAILED = "IVAI-ASR-CONFIG-005"
}

/** Thrown by the ASR config boundary when a snapshot cannot be built. */
class AsrConfigException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Persistence boundary for the encrypted ASR API key. Implementations must never
 * store or expose the plaintext (IVI-IVAI-DSN-CR-006, mirror of model-client).
 */
interface AsrSecretStore {
    suspend fun status(): KeyStatus
    suspend fun write(key: String)
    suspend fun read(): String?
    suspend fun clear()
}
