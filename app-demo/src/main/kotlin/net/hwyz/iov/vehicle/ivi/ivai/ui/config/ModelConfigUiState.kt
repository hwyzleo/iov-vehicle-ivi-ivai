package net.hwyz.iov.vehicle.ivi.ivai.ui.config

import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus

/**
 * Model config screen state (IVI-IVAI-DSN-CR-003).
 *
 * Security rules:
 *  - the saved key is never back-filled; only [keyStatus] (SET/NOT_SET/INVALID)
 *  - [apiKeyDraft] holds only what the user typed in this session
 *  - replacing and clearing the key are explicit, separate actions
 */
data class ModelConfigUiState(
    val baseUrl: String = "",
    val keyStatus: KeyStatus = KeyStatus.NOT_SET,
    val apiKeyDraft: String = "",
    val showApiKey: Boolean = false,
    val clearKeyRequested: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val isTesting: Boolean = false,
    val isSaving: Boolean = false,
    val validationErrors: Map<String, String> = emptyMap(),
    val message: String? = null
)
