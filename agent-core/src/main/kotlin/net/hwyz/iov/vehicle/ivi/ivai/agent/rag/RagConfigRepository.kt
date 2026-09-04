package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import kotlinx.coroutines.flow.StateFlow

/** Result of saving / resetting the RAG configuration. */
sealed interface RagSaveResult {
    data class Success(val configVersion: Long) : RagSaveResult
    data class Failure(val errorCode: String, val message: String) : RagSaveResult
}

/** RAG config persistence errors (IVI-IVAI-DSN-CR-005). */
object RagConfigErrorCode {
    const val PERSISTENCE_FAILED = "IVAI-RAG-CONFIG-001"
    const val MISSING_REQUIRED = "IVAI-RAG-CONFIG-002"
    const val VERSION_INCOMPATIBLE = "IVAI-RAG-CONFIG-003"
}

/** Published RAG configuration state consumed by the UI and diagnostics. */
sealed interface RagConfigState {
    /** Initial load in progress. */
    data object Loading : RagConfigState

    /** A valid RAG configuration is effective. */
    data class Valid(val config: RagRuntimeConfig) : RagConfigState

    /** Persisted config is unusable; falls back to defaults. */
    data class Invalid(val reason: String, val errorCode: String) : RagConfigState
}

/**
 * Central RAG runtime configuration repository (IVI-IVAI-DSN-CR-005).
 * The concrete persistence (DataStore) lives in the Android service module.
 */
interface RagConfigRepository {

    /** Published configuration state for UI and diagnostics. */
    val configState: StateFlow<RagConfigState>

    /** Current effective configuration; defaults when nothing persisted yet. */
    suspend fun loadSnapshot(): RagRuntimeConfig

    /** Validates + persists the draft and bumps the configVersion. */
    suspend fun save(draft: RagConfigDraft): RagSaveResult

    /** Confirmed reset: back to defaults (disabled). */
    suspend fun resetToDefault(): RagSaveResult
}
