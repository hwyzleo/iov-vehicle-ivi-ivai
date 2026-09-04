package net.hwyz.iov.vehicle.ivi.ivai.ui.config

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
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigState
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult

/**
 * Model config ViewModel (IVI-IVAI-DSN-CR-003): pre-fills the current config,
 * tracks unsaved changes, blocks duplicate submit while testing/saving, maps
 * validation errors and preserves the saved key unless the user explicitly
 * replaces or clears it.
 */
class ModelConfigViewModel : ViewModel() {

    private val _state = MutableStateFlow(ModelConfigUiState())
    val state: StateFlow<ModelConfigUiState> = _state.asStateFlow()

    /** One-shot "user asked to reset" events; the Activity shows the confirm dialog. */
    private val _resetRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resetRequests: SharedFlow<Unit> = _resetRequests.asSharedFlow()

    private var gateway: ModelConfigGateway? = null
    private var configJob: Job? = null

    /** Effective baseUrl last seen from the repository (dirty-check baseline). */
    private var savedBaseUrl: String? = null

    fun attach(gateway: ModelConfigGateway) {
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

    fun onAction(action: ModelConfigUiAction) {
        when (action) {
            is ModelConfigUiAction.BaseUrlChanged -> {
                _state.update { it.copy(baseUrl = action.value, validationErrors = emptyMap()).recomputeDirty() }
            }
            is ModelConfigUiAction.ApiKeyChanged -> {
                _state.update {
                    it.copy(apiKeyDraft = action.value, clearKeyRequested = false, validationErrors = emptyMap())
                        .recomputeDirty()
                }
            }
            ModelConfigUiAction.ToggleApiKeyVisibility -> {
                _state.update { it.copy(showApiKey = !it.showApiKey) }
            }
            ModelConfigUiAction.ToggleClearKeyRequested -> {
                _state.update { it.copy(clearKeyRequested = !it.clearKeyRequested).recomputeDirty() }
            }
            ModelConfigUiAction.TestConnectionClicked -> testConnection()
            ModelConfigUiAction.SaveClicked -> save()
            ModelConfigUiAction.DismissMessage -> _state.update { it.copy(message = null) }
        }
    }

    /** Activity calls this after the user confirmed the reset dialog. */
    fun requestReset() {
        _resetRequests.tryEmit(Unit)
    }

    /** Activity calls this after the user confirmed the reset dialog. */
    fun confirmReset() {
        val g = gateway
        if (g == null) {
            _state.update { it.copy(message = "模型服务未连接，请稍后重试") }
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

    private fun onConfigState(state: ModelConfigState) {
        when (state) {
            is ModelConfigState.Loading -> Unit
            is ModelConfigState.Valid -> {
                savedBaseUrl = state.config.baseUrl.toString()
                // Only pre-fill once so later emissions never clobber user typing.
                _state.update {
                    if (it.baseUrl.isEmpty()) {
                        it.copy(baseUrl = savedBaseUrl ?: "").recomputeDirty()
                    } else {
                        it.recomputeDirty()
                    }
                }
            }
            is ModelConfigState.Invalid -> {
                _state.update { it.copy(message = "当前配置无效：${state.reason}") }
            }
        }
    }

    private fun testConnection() {
        val g = gateway
        if (g == null) {
            _state.update { it.copy(message = "模型服务未连接，请稍后重试") }
            return
        }
        val current = _state.value
        if (current.isTesting || current.isSaving) return
        _state.update { it.copy(isTesting = true, message = null, validationErrors = emptyMap()) }
        viewModelScope.launch {
            try {
                val result = g.testConnection(buildDraft())
                _state.update {
                    it.copy(
                        isTesting = false,
                        message = describeTestResult(result)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(isTesting = false, message = "连接测试失败：${e.message}")
                }
            }
        }
    }

    private fun save() {
        val g = gateway
        if (g == null) {
            _state.update { it.copy(message = "模型服务未连接，请稍后重试") }
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
                        savedBaseUrl = draft.baseUrl.trim()
                        _state.update {
                            it.copy(
                                isSaving = false,
                                apiKeyDraft = "",
                                clearKeyRequested = false,
                                validationErrors = emptyMap(),
                                message = "保存成功，新的模型请求将立即使用该配置"
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

    private fun buildDraft(): ModelConfigDraft {
        val s = _state.value
        val action = when {
            s.clearKeyRequested -> ApiKeyAction.Clear
            s.apiKeyDraft.isNotBlank() -> ApiKeyAction.Replace(s.apiKeyDraft)
            else -> ApiKeyAction.Keep
        }
        return ModelConfigDraft(baseUrl = s.baseUrl, apiKeyAction = action)
    }

    private fun describeTestResult(result: ConnectionTestResult): String = when (result) {
        is ConnectionTestResult.Success -> "连接测试成功（未保存，点击“保存”生效）"
        is ConnectionTestResult.NetworkError -> "连接测试失败：网络不可达（${result.detail}）"
        is ConnectionTestResult.Timeout -> "连接测试失败：超时"
        is ConnectionTestResult.Unauthorized -> "连接测试失败：鉴权失败（401/403）"
        is ConnectionTestResult.InvalidResponse -> "连接测试失败：${result.detail}"
    }

    private fun ModelConfigUiState.recomputeDirty(): ModelConfigUiState {
        val saved = savedBaseUrl
        // 仓库保存时会做 URL 规范化（如补尾斜杠），比较时忽略该差异，避免保存成功后误报未保存。
        val dirty = saved == null ||
            baseUrl.trim().trimEnd('/') != saved.trim().trimEnd('/') ||
            apiKeyDraft.isNotBlank() ||
            clearKeyRequested
        return copy(hasUnsavedChanges = dirty)
    }
}
