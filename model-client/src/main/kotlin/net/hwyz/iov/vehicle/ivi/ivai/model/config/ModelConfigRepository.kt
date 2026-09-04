package net.hwyz.iov.vehicle.ivi.ivai.model.config

import kotlinx.coroutines.flow.StateFlow

/** Result of saving / resetting configuration. */
sealed interface SaveResult {
    data class Success(val configVersion: Long) : SaveResult
    data class Failure(val errorCode: String, val message: String) : SaveResult
}

/**
 * Central LLM runtime configuration repository (IVI-IVAI-DSN-CR-003).
 *
 * `save()` must validate, encrypt and persist inside a single mutex region, and
 * only publish a new configVersion after every step succeeded — a failure at any
 * step must never corrupt the currently effective configuration.
 */
interface ModelConfigRepository : ModelConfigSnapshotProvider {

    /** Published configuration state (Valid / Invalid) for UI and diagnostics. */
    val configState: StateFlow<ModelConfigState>

    /** Current effective snapshot; throws [ModelConfigException] when invalid. */
    override suspend fun loadSnapshot(): ModelRuntimeConfig

    /** Saved key status — never the plaintext. */
    suspend fun keyStatus(): KeyStatus

    /** Validates the draft without persisting anything. */
    suspend fun validate(draft: ModelConfigDraft): ValidationResult

    /** Runs a connectivity test against the draft without saving it. */
    suspend fun testConnection(draft: ModelConfigDraft): ConnectionTestResult

    /** Validates + encrypts + persists the draft; does not overwrite on failure. */
    suspend fun save(draft: ModelConfigDraft): SaveResult

    /** Confirmed reset: restores the default address and clears the saved key. */
    suspend fun resetToDefault(): SaveResult
}
