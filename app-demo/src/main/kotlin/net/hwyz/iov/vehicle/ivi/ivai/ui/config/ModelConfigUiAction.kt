package net.hwyz.iov.vehicle.ivi.ivai.ui.config

/** User actions on the model config screen (IVI-IVAI-DSN-CR-003). */
sealed interface ModelConfigUiAction {
    data class BaseUrlChanged(val value: String) : ModelConfigUiAction
    data class ApiKeyChanged(val value: String) : ModelConfigUiAction
    data object ToggleApiKeyVisibility : ModelConfigUiAction

    /** Explicitly request clearing the saved key (checked state in the UI). */
    data object ToggleClearKeyRequested : ModelConfigUiAction

    data object TestConnectionClicked : ModelConfigUiAction
    data object SaveClicked : ModelConfigUiAction
    data object DismissMessage : ModelConfigUiAction
}
