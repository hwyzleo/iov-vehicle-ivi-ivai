package net.hwyz.iov.vehicle.ivi.ivai.speech.config

import kotlinx.serialization.Serializable
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretValue

/**
 * Non-sensitive ASR runtime configuration persisted in plain storage (DataStore).
 * Secrets (apiKey) are never part of this model — they live in
 * [AsrSecretStore] (IVI-IVAI-DSN-CR-006).
 */
@Serializable
data class AsrPublicConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val providerType: AsrProviderType = AsrProviderType.ANDROID_ON_DEVICE,
    /** Required only for HTTP_COMPATIBLE / VENDOR providers. */
    val baseUrl: String? = null,
    /** Required only for HTTP_COMPATIBLE / VENDOR providers. */
    val modelName: String? = null,
    val languageTag: String = DEFAULT_LANGUAGE_TAG,
    /** Preference only — never an offline guarantee. */
    val preferOffline: Boolean = true,
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val recognitionTimeoutMs: Long = DEFAULT_RECOGNITION_TIMEOUT_MS,
    val fallbackPolicy: AsrFallbackPolicy = AsrFallbackPolicy.TEXT_ONLY,
    val configVersion: Long = 0L
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_LANGUAGE_TAG = "zh-CN"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L
        const val DEFAULT_RECOGNITION_TIMEOUT_MS = 30_000L
    }
}

/**
 * Immutable, in-memory runtime snapshot composed by the repository. Captured
 * before each ACTION_DOWN; never stored in long-lived state (IVI-IVAI-DSN-CR-006).
 */
data class AsrRuntimeConfig(
    val public: AsrPublicConfig,
    val apiKey: SecretValue?,
    val version: Long
)
