package net.hwyz.iov.vehicle.ivi.ivai.model.config

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProviderType

/**
 * Non-sensitive LLM runtime configuration persisted in plain storage (DataStore).
 * Secrets (apiKey) are never part of this model — they live in [SecretStore].
 * (IVI-IVAI-DSN-CR-003, schema v2 per CR-004)
 *
 * Schema history:
 *  - v1: baseUrl + updatedAt + configVersion
 *  - v2: + providerType, endpointPath, modelName (CR-004). v1 is migrated by the
 *    repository: providerType = OLLAMA, original baseUrl kept, modelName defaults
 *    to the current Ollama default model.
 */
@Serializable
data class ModelPublicConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val baseUrl: String,
    val providerType: ModelProviderType = ModelProviderType.OLLAMA,
    val endpointPath: String? = null,
    val modelName: String? = null,
    val updatedAt: Long = 0L,
    val configVersion: Long = 0L
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val SCHEMA_VERSION_V1 = 1
    }
}

/**
 * Immutable, in-memory runtime configuration snapshot composed by the repository.
 * Valid only for the duration of the request it was captured for; never stored
 * in long-lived state.
 */
data class ModelRuntimeConfig(
    val baseUrl: HttpUrl,
    val providerType: ModelProviderType = ModelProviderType.OLLAMA,
    val endpointPath: String? = null,
    val modelName: String? = null,
    val apiKey: SecretValue?,
    val version: Long
)

/**
 * Wrapper that guarantees the raw secret never leaks through [toString], logs or
 * state copies (IVI-IVAI-DSN-CR-003 / IVAI-REQ-027). Access the raw value only
 * via [use] and consume it immediately (e.g. build an Authorization header).
 */
class SecretValue private constructor(private val value: String) {

    override fun toString(): String = MASK

    /** Runs [block] with the raw secret. Never store the result into state or logs. */
    fun <R> use(block: (String) -> R): R = block(value)

    companion object {
        const val MASK = "***"

        fun of(raw: String): SecretValue = SecretValue(raw)
    }
}

/** Saved API key status reported to the UI — never the plaintext. */
enum class KeyStatus { SET, NOT_SET, INVALID }

/**
 * User-editable draft from the config screen. The apiKey change is explicit so
 * that an address-only save never wipes the saved key: [ApiKeyAction.Keep]
 * preserves it, [ApiKeyAction.Replace] overwrites it, [ApiKeyAction.Clear] removes it.
 */
data class ModelConfigDraft(
    val baseUrl: String,
    val providerType: ModelProviderType = ModelProviderType.OLLAMA,
    val modelName: String? = null,
    val endpointPath: String? = null,
    val apiKeyAction: ApiKeyAction = ApiKeyAction.Keep
)

sealed interface ApiKeyAction {
    /** Keep the currently saved key (address-only change). */
    data object Keep : ApiKeyAction

    /** Replace the saved key with [value]. */
    data class Replace(val value: String) : ApiKeyAction

    /** Remove the saved key. */
    data object Clear : ApiKeyAction
}
