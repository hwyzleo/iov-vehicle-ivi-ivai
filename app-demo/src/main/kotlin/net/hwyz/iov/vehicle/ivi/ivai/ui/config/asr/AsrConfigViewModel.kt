package net.hwyz.iov.vehicle.ivi.ivai.ui.config.asr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrConfigState
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrProviderType

/**
 * ASR config ViewModel (IVI-IVAI-DSN-CR-006): pre-fills the current config,
 * tracks unsaved changes, blocks duplicate submit while testing/saving, maps
 * validation errors and preserves the saved key unless the user explicitly
 * replaces or clears it. Switching the provider re-validates fields but never
 * auto-saves or clears the saved key.
 */
class AsrConfigViewModel : ViewModel() {

    private val _state = MutableStateFlow(AsrConfigUiState())
    val state: StateFlow<AsrConfigUiState> = _state.asStateFlow()

    /** One-shot "user asked to reset" events; the Activity shows the confirm dialog. */
    private val _resetRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resetRequests: SharedFlow<Unit> = _resetRequests.asSharedFlow()

    private var gateway: AsrConfigGateway? = null
    private var configJob: Job? = null

    /** True once the current config has been pre-filled into the UI. */
    private var prefilled = false

    /** Effective values last seen from the repository (dirty-check baseline). */
    private var savedProviderType: AsrProviderType? = null
    private var savedBaseUrl: String? = null
    private var savedModelName: String? = null
    private var savedLanguageTag: String? = null
    private var savedPreferOffline: Boolean? = null
    private var savedConnectTimeout: String? = null
    private var savedRecognitionTimeout: String? = null
    private var savedFallbackPolicy: String? = null

    fun attach(gateway: AsrConfigGateway) {
        this.gateway = gateway
        if (configJob == null) {
            configJob = viewModelScope.launch {
                gateway.configState.collect { onConfigState(it) }
            }
        }
        viewModelScope.launch {
            runCatching { gateway.keyStatus() }.onSuccess { status ->
                _state.update { it.copy(keyStatus = status) }
            }
        }
    }

    fun detach() {
        gateway = null
    }

    fun onAction(action: AsrConfigUiAction) {
        when (action) {
            is AsrConfigUiAction.ProviderTypeChanged -> {
                _state.update {
                    it.copy(providerType = action.providerType, validationErrors = emptyMap())
                        .recomputeDirty()
                }
            }
            is AsrConfigUiAction.BaseUrlChanged -> {
                _state.update { it.copy(baseUrl = action.value, validationErrors = emptyMap()).recomputeDirty() }
            }
            is AsrConfigUiAction.ModelNameChanged -> {
                _state.update { it.copy(modelName = action.value, validationErrors = emptyMap()).recomputeDirty() }
            }
            is AsrConfigUiAction.LanguageChanged -> {
                _state.update { it.copy(languageTag = action.value, validationErrors = emptyMap()).recomputeDirty() }
            }
            is AsrConfigUiAction.PreferOfflineChanged -> {
                _state.update { it.copy(preferOffline = action.value, validationErrors = emptyMap()).recomputeDirty() }
            }
            is AsrConfigUiAction.ConnectTimeoutChanged -> {
                _state.update {
                    it.copy(connectTimeoutMs = action.value, validationErrors = emptyMap()).recomputeDirty()
                }
            }
            is AsrConfigUiAction.RecognitionTimeoutChanged -> {
                _state.update {
                    it.copy(recognitionTimeoutMs = action.value, validationErrors = emptyMap()).recomputeDirty()
                }
            }
            is AsrConfigUiAction.FallbackPolicyChanged -> {
                _state.update {
                    it.copy(fallbackPolicy = action.policy, validationErrors = emptyMap()).recomputeDirty()
                }
            }
            is AsrConfigUiAction.ApiKeyChanged -> {
                _state.update {
                    it.copy(apiKeyDraft = action.value, clearKeyRequested = false, validationErrors = emptyMap())
                        .recomputeDirty()
                }
            }
            AsrConfigUiAction.ToggleApiKeyVisibility -> {
                _state.update { it.copy(showApiKey = !it.showApiKey) }
            }
            AsrConfigUiAction.ToggleClearKeyRequested -> {
                _state.update { it.copy(clearKeyRequested = !it.clearKeyRequested).recomputeDirty() }
            }
            AsrConfigUiAction.TestConnectionClicked -> testConnection()
            AsrConfigUiAction.SaveClicked -> save()
            AsrConfigUiAction.DismissMessage -> _state.update { it.copy(message = null) }
        }
    }

    fun requestReset() {
        _resetRequests.tryEmit(Unit)
    }

    fun confirmReset() {
        val g = gateway
        if (g == null) {
            _state.update { it.copy(message = "语音服务未连接，请稍后重试") }
            return
        }
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true, message = null) }
        viewModelScope.launch {
            try {
                when (val result = g.resetToDefault()) {
                    is SaveResult.Success -> {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                providerType = AsrProviderType.ANDROID_ON_DEVICE,
                                baseUrl = "",
                                modelName = "",
                                languageTag = "zh-CN",
                                preferOffline = true,
                                connectTimeoutMs = "5000",
                                recognitionTimeoutMs = "30000",
                                fallbackPolicy = net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrFallbackPolicy.TEXT_ONLY,
                                apiKeyDraft = "",
                                clearKeyRequested = false,
                                validationErrors = emptyMap(),
                                message = "已恢复默认配置"
                            ).recomputeDirty()
                        }
                    }
                    is SaveResult.Failure -> {
                        _state.update { it.copy(isSaving = false, message = "恢复默认失败：${result.message}") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, message = "恢复默认失败：${e.message}") }
            }
        }
    }

    private fun onConfigState(state: AsrConfigState) {
        when (state) {
            is AsrConfigState.Loading -> Unit
            is AsrConfigState.Valid -> {
                val p = state.config.public
                savedProviderType = p.providerType
                savedBaseUrl = p.baseUrl
                savedModelName = p.modelName
                savedLanguageTag = p.languageTag
                savedPreferOffline = p.preferOffline
                savedConnectTimeout = p.connectTimeoutMs.toString()
                savedRecognitionTimeout = p.recognitionTimeoutMs.toString()
                savedFallbackPolicy = p.fallbackPolicy.name
                if (!prefilled) {
                    prefilled = true
                    // Only pre-fill once so later emissions never clobber user typing.
                    _state.update {
                        it.copy(
                            providerType = p.providerType,
                            baseUrl = p.baseUrl ?: "",
                            modelName = p.modelName ?: "",
                            languageTag = p.languageTag,
                            preferOffline = p.preferOffline,
                            connectTimeoutMs = p.connectTimeoutMs.toString(),
                            recognitionTimeoutMs = p.recognitionTimeoutMs.toString(),
                            fallbackPolicy = p.fallbackPolicy
                        ).recomputeDirty()
                    }
                } else {
                    _state.update { it.recomputeDirty() }
                }
            }
            is AsrConfigState.Invalid -> {
                _state.update { it.copy(message = "当前配置无效：${state.reason}") }
            }
        }
    }

    private fun testConnection() {
        val g = gateway
        if (g == null) {
            _state.update { it.copy(message = "语音服务未连接，请稍后重试") }
            return
        }
        val current = _state.value
        if (current.isTesting || current.isSaving) return
        _state.update { it.copy(isTesting = true, message = null, validationErrors = emptyMap()) }
        viewModelScope.launch {
            try {
                val result = g.testConnection(buildDraft())
                _state.update { it.copy(isTesting = false, message = describeTestResult(result)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isTesting = false, message = "连接测试失败：${e.message}") }
            }
        }
    }

    private fun save() {
        val g = gateway
        if (g == null) {
            _state.update { it.copy(message = "语音服务未连接，请稍后重试") }
            return
        }
        val current = _state.value
        if (current.isTesting || current.isSaving) return
        _state.update { it.copy(isSaving = true, message = null, validationErrors = emptyMap()) }
        viewModelScope.launch {
            try {
                val draft = buildDraft()
                val validation = g.validate(draft)
                if (validation is ValidationResult.Invalid) {
                    _state.update {
                        it.copy(
                            isSaving = false,
                            validationErrors = validation.errors.associate { error -> error.field to error.message }
                        )
                    }
                    return@launch
                }

                when (val result = g.save(draft)) {
                    is SaveResult.Success -> {
                        savedProviderType = draft.providerType
                        savedBaseUrl = draft.baseUrl.trim()
                        savedModelName = draft.modelName.trim()
                        savedLanguageTag = draft.languageTag.trim()
                        savedPreferOffline = draft.preferOffline
                        savedConnectTimeout = draft.connectTimeoutMs.toString()
                        savedRecognitionTimeout = draft.recognitionTimeoutMs.toString()
                        savedFallbackPolicy = draft.fallbackPolicy.name
                        _state.update {
                            it.copy(
                                isSaving = false,
                                apiKeyDraft = "",
                                clearKeyRequested = false,
                                validationErrors = emptyMap(),
                                message = "保存成功，新的语音会话将使用该配置"
                            ).recomputeDirty()
                        }
                    }
                    is SaveResult.Failure -> {
                        _state.update { it.copy(isSaving = false, message = "保存失败：${result.message}") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, message = "保存失败：${e.message}") }
            }
        }
    }

    private fun buildDraft(): AsrConfigDraft {
        val s = _state.value
        val action = when {
            s.clearKeyRequested -> ApiKeyAction.Clear
            s.apiKeyDraft.isNotBlank() -> ApiKeyAction.Replace(s.apiKeyDraft)
            else -> ApiKeyAction.Keep
        }
        return AsrConfigDraft(
            providerType = s.providerType,
            baseUrl = s.baseUrl,
            modelName = s.modelName,
            languageTag = s.languageTag.trim().ifEmpty { "zh-CN" },
            preferOffline = s.preferOffline,
            connectTimeoutMs = s.connectTimeoutMs.toLongOrNull() ?: 0L,
            recognitionTimeoutMs = s.recognitionTimeoutMs.toLongOrNull() ?: 0L,
            fallbackPolicy = s.fallbackPolicy,
            apiKeyAction = action
        )
    }

    private fun describeTestResult(result: ConnectionTestResult): String = when (result) {
        is ConnectionTestResult.Success ->
            "连接测试成功（${result.testMethod ?: "health"}，未保存，点击“保存”生效）"
        is ConnectionTestResult.NetworkError -> "连接测试失败：网络不可达（${result.detail}）"
        is ConnectionTestResult.Timeout -> "连接测试失败：超时"
        is ConnectionTestResult.Unauthorized ->
            "连接测试失败：鉴权失败（401/403）。请确认 API Key 正确——若已保存过密钥，请重新输入后点“保存”再测试"
        is ConnectionTestResult.InvalidResponse ->
            "连接测试失败：${result.detail}${result.testMethod?.let { "（$it）" } ?: ""}"
    }

    private fun AsrConfigUiState.recomputeDirty(): AsrConfigUiState {
        val dirty = savedProviderType == null ||
            providerType != (savedProviderType ?: AsrProviderType.ANDROID_ON_DEVICE) ||
            baseUrl.trim().trimEnd('/') != (savedBaseUrl?.trim()?.trimEnd('/') ?: "") ||
            modelName.trim() != (savedModelName?.trim() ?: "") ||
            languageTag.trim() != (savedLanguageTag?.trim() ?: "") ||
            preferOffline != (savedPreferOffline ?: true) ||
            connectTimeoutMs.trim() != (savedConnectTimeout?.trim() ?: "") ||
            recognitionTimeoutMs.trim() != (savedRecognitionTimeout?.trim() ?: "") ||
            fallbackPolicy.name != (savedFallbackPolicy ?: "") ||
            apiKeyDraft.isNotBlank() ||
            clearKeyRequested
        return copy(hasUnsavedChanges = dirty)
    }
}
