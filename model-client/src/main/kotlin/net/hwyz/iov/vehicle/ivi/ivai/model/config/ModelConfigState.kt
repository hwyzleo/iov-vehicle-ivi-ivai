package net.hwyz.iov.vehicle.ivi.ivai.model.config

/**
 * Published configuration state consumed by the UI and diagnostics
 * (IVI-IVAI-DSN-CR-003).
 */
sealed interface ModelConfigState {

    /** Initial load in progress. */
    data object Loading : ModelConfigState

    /** A valid runtime configuration is effective and may be used for requests. */
    data class Valid(val config: ModelRuntimeConfig) : ModelConfigState

    /**
     * Configuration is unusable (missing, corrupt, incompatible or undecryptable).
     * Model requests must be refused until the user re-configures or resets.
     */
    data class Invalid(val reason: String, val errorCode: String) : ModelConfigState
}

/** IVAI-CONFIG-* error codes (IVI-IVAI-DSN-CR-003). */
object ModelConfigErrorCode {
    const val INVALID_URL = "IVAI-CONFIG-001"
    const val MISSING_REQUIRED = "IVAI-CONFIG-002"
    const val PERSISTENCE_FAILED = "IVAI-CONFIG-003"
    const val VERSION_INCOMPATIBLE = "IVAI-CONFIG-004"
    const val ENCRYPTION_FAILED = "IVAI-CONFIG-005"
    const val DECRYPTION_FAILED = "IVAI-CONFIG-006"
    const val TEST_TIMEOUT = "IVAI-CONFIG-007"
    const val TEST_UNAUTHORIZED = "IVAI-CONFIG-008"
}
