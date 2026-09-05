package net.hwyz.iov.vehicle.ivi.ivai.ui.config.asr

import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrFallbackPolicy
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrProviderType

/**
 * ASR config screen state (IVI-IVAI-DSN-CR-006).
 *
 * Security rules: the saved key is never back-filled; [apiKeyDraft] holds only
 * what the user typed this session; replacing / clearing the key are explicit,
 * separate actions. Android providers hide address / model / key fields.
 */
data class AsrConfigUiState(
    val providerType: AsrProviderType = AsrProviderType.ANDROID_ON_DEVICE,
    val baseUrl: String = "",
    val modelName: String = "",
    val languageTag: String = "zh-CN",
    val preferOffline: Boolean = true,
    val connectTimeoutMs: String = "5000",
    val recognitionTimeoutMs: String = "30000",
    val fallbackPolicy: AsrFallbackPolicy = AsrFallbackPolicy.TEXT_ONLY,
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

/** User intents dispatched from the ASR config screen to the ViewModel. */
sealed interface AsrConfigUiAction {
    data class ProviderTypeChanged(val providerType: AsrProviderType) : AsrConfigUiAction
    data class BaseUrlChanged(val value: String) : AsrConfigUiAction
    data class ModelNameChanged(val value: String) : AsrConfigUiAction
    data class LanguageChanged(val value: String) : AsrConfigUiAction
    data class PreferOfflineChanged(val value: Boolean) : AsrConfigUiAction
    data class ConnectTimeoutChanged(val value: String) : AsrConfigUiAction
    data class RecognitionTimeoutChanged(val value: String) : AsrConfigUiAction
    data class FallbackPolicyChanged(val policy: AsrFallbackPolicy) : AsrConfigUiAction
    data class ApiKeyChanged(val value: String) : AsrConfigUiAction
    data object ToggleApiKeyVisibility : AsrConfigUiAction
    data object ToggleClearKeyRequested : AsrConfigUiAction
    data object TestConnectionClicked : AsrConfigUiAction
    data object SaveClicked : AsrConfigUiAction
    data object DismissMessage : AsrConfigUiAction
}
