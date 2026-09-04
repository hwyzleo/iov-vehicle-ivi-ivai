package net.hwyz.iov.vehicle.ivi.ivai.ui.config

import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProviderType

/** User actions on the model config screen (IVI-IVAI-DSN-CR-003 / CR-004). */
sealed interface ModelConfigUiAction {
    data class BaseUrlChanged(val value: String) : ModelConfigUiAction

    /** Provider switch re-runs field validation; it never saves or clears the key. */
    data class ProviderTypeChanged(val value: ModelProviderType) : ModelConfigUiAction
    data class ModelNameChanged(val value: String) : ModelConfigUiAction

    /** Endpoint Path is an advanced option, hidden by default. */
    data class EndpointPathChanged(val value: String) : ModelConfigUiAction
    data object ToggleAdvancedOptions : ModelConfigUiAction

    data class ApiKeyChanged(val value: String) : ModelConfigUiAction
    data object ToggleApiKeyVisibility : ModelConfigUiAction

    /** Explicitly request clearing the saved key (checked state in the UI). */
    data object ToggleClearKeyRequested : ModelConfigUiAction

    data object TestConnectionClicked : ModelConfigUiAction
    data object SaveClicked : ModelConfigUiAction
    data object DismissMessage : ModelConfigUiAction
}
