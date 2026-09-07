package net.hwyz.iov.vehicle.ivi.ivai.ui.config.embedding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagSaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.DistanceMetric

/**
 * 嵌入模型配置 ViewModel（CR-011 补齐 · 仿 ModelConfigViewModel）：预填当前
 * 配置、跟踪未保存修改、测试连通性、校验并保存；密钥不回填明文。
 */
class EmbeddingConfigViewModel : ViewModel() {

    private val _state = MutableStateFlow(EmbeddingConfigUiState())
    val state: StateFlow<EmbeddingConfigUiState> = _state.asStateFlow()

    /** 一次性「请求恢复默认」事件；Activity 弹确认框。 */
    private val _resetRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resetRequests: SharedFlow<Unit> = _resetRequests.asSharedFlow()

    private var gateway: EmbeddingConfigGateway? = null

    /** 上次从仓库读到的生效配置（dirty-check 基线），密钥除外。 */
    private var savedConfig: EmbeddingConfig? = null

    fun attach(gateway: EmbeddingConfigGateway) {
        this.gateway = gateway
        viewModelScope.launch {
            runCatching { gateway.load() }.onSuccess { config ->
                savedConfig = config
                // 只预填一次，避免后续发射覆盖用户正在输入的内容。
                _state.update { st ->
                    if (st.baseUrl.isEmpty() && st.modelId.isEmpty() && !st.everPrefilled) {
                        st.copy(
                            baseUrl = config.baseUrl,
                            modelId = config.modelId,
                            modelVersion = config.modelVersion ?: "",
                            dimension = config.dimension.toString(),
                            timeoutMs = config.timeoutMs.toString(),
                            maxRetries = config.maxRetries.toString(),
                            batchSize = config.batchSize.toString(),
                            allowedHosts = config.allowedHosts.joinToString(", "),
                            everPrefilled = true
                        ).recomputeDirty()
                    } else {
                        st.copy(everPrefilled = true).recomputeDirty()
                    }
                }
            }.onFailure {
                _state.update { it.copy(message = "加载嵌入配置失败：${it.message}") }
            }
        }
        viewModelScope.launch {
            runCatching { gateway.keyStatus() }.onSuccess { status ->
                _state.update { it.copy(keyStatus = status) }
            }
        }
    }

    fun onAction(action: EmbeddingConfigUiAction) {
        when (action) {
            is EmbeddingConfigUiAction.BaseUrlChanged ->
                _state.update { it.copy(baseUrl = action.value, validationErrors = emptyMap()).recomputeDirty() }
            is EmbeddingConfigUiAction.ModelIdChanged ->
                _state.update { it.copy(modelId = action.value, validationErrors = emptyMap()).recomputeDirty() }
            is EmbeddingConfigUiAction.ModelVersionChanged ->
                _state.update { it.copy(modelVersion = action.value, validationErrors = emptyMap()).recomputeDirty() }
            is EmbeddingConfigUiAction.DimensionChanged ->
                _state.update { it.copy(dimension = action.value, validationErrors = emptyMap()).recomputeDirty() }
            is EmbeddingConfigUiAction.TimeoutMsChanged ->
                _state.update { it.copy(timeoutMs = action.value, validationErrors = emptyMap()).recomputeDirty() }
            is EmbeddingConfigUiAction.MaxRetriesChanged ->
                _state.update { it.copy(maxRetries = action.value, validationErrors = emptyMap()).recomputeDirty() }
            is EmbeddingConfigUiAction.BatchSizeChanged ->
                _state.update { it.copy(batchSize = action.value, validationErrors = emptyMap()).recomputeDirty() }
            is EmbeddingConfigUiAction.AllowedHostsChanged ->
                _state.update { it.copy(allowedHosts = action.value, validationErrors = emptyMap()).recomputeDirty() }
            is EmbeddingConfigUiAction.ApiKeyChanged ->
                _state.update {
                    it.copy(apiKeyDraft = action.value, clearKeyRequested = false, validationErrors = emptyMap())
                        .recomputeDirty()
                }
            EmbeddingConfigUiAction.ToggleApiKeyVisibility ->
                _state.update { it.copy(showApiKey = !it.showApiKey) }
            EmbeddingConfigUiAction.ToggleClearKeyRequested ->
                _state.update { it.copy(clearKeyRequested = !it.clearKeyRequested).recomputeDirty() }
            EmbeddingConfigUiAction.TestConnectionClicked -> testConnection()
            EmbeddingConfigUiAction.SaveClicked -> save()
            EmbeddingConfigUiAction.DismissMessage -> _state.update { it.copy(message = null) }
        }
    }

    /** Activity 在用户确认恢复默认后调用。 */
    fun requestReset() {
        _resetRequests.tryEmit(Unit)
    }

    /** Activity 在用户确认恢复默认后调用。 */
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
                    is RagSaveResult.Success -> {
                        savedConfig = EmbeddingConfig()
                        _state.update {
                            it.copy(
                                isSaving = false,
                                baseUrl = "",
                                modelId = "",
                                modelVersion = "",
                                dimension = "0",
                                timeoutMs = DEFAULT_TIMEOUT_MS.toString(),
                                maxRetries = DEFAULT_MAX_RETRIES.toString(),
                                batchSize = DEFAULT_BATCH_SIZE.toString(),
                                allowedHosts = "",
                                apiKeyDraft = "",
                                clearKeyRequested = false,
                                validationErrors = emptyMap(),
                                message = "已恢复默认（回退本地嵌入）"
                            ).recomputeDirty()
                        }
                    }
                    is RagSaveResult.Failure ->
                        _state.update { it.copy(isSaving = false, message = "恢复默认失败：${result.message}") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, message = "恢复默认失败：${e.message}") }
            }
        }
    }

    private fun testConnection() {
        val g = gateway ?: return _state.update { it.copy(message = "模型服务未连接，请稍后重试") }
        val current = _state.value
        if (current.isTesting || current.isSaving) return

        val draft = buildDraft() ?: return
        _state.update { it.copy(isTesting = true, message = null, validationErrors = emptyMap()) }
        viewModelScope.launch {
            try {
                val result = g.testConnection(draft.first, draft.second)
                _state.update {
                    it.copy(isTesting = false, message = describeTestResult(result))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isTesting = false, message = "连接测试失败：${e.message}") }
            }
        }
    }

    private fun save() {
        val g = gateway ?: return _state.update { it.copy(message = "模型服务未连接，请稍后重试") }
        val current = _state.value
        if (current.isTesting || current.isSaving) return

        val draft = buildDraft() ?: return
        _state.update { it.copy(isSaving = true, message = null, validationErrors = emptyMap()) }
        viewModelScope.launch {
            try {
                val (config, action) = draft
                val validation = g.validate(config)
                if (validation is ValidationResult.Invalid) {
                    _state.update {
                        it.copy(
                            isSaving = false,
                            validationErrors = validation.errors.associate { error -> error.field to error.message }
                        )
                    }
                    return@launch
                }

                when (val result = g.save(config, action)) {
                    is RagSaveResult.Success -> {
                        savedConfig = config
                        _state.update {
                            it.copy(
                                isSaving = false,
                                apiKeyDraft = "",
                                clearKeyRequested = false,
                                validationErrors = emptyMap(),
                                message = "保存成功，新的检索请求将立即使用该嵌入模型"
                            ).recomputeDirty()
                        }
                    }
                    is RagSaveResult.Failure ->
                        _state.update { it.copy(isSaving = false, message = "保存失败：${result.message}") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, message = "保存失败：${e.message}") }
            }
        }
    }

    /**
     * 从 UI 状态组装 EmbeddingConfig 与密钥动作。数值字段解析失败 → 返回 null 并
     * 写入字段错误（不发起网络/持久化）。
     */
    private fun buildDraft(): Pair<EmbeddingConfig, ApiKeyAction>? {
        val s = _state.value
        val dimension = s.dimension.trim().toIntOrNull()
        val timeoutMs = s.timeoutMs.trim().toLongOrNull()
        val maxRetries = s.maxRetries.trim().toIntOrNull()
        val batchSize = s.batchSize.trim().toIntOrNull()

        val errors = mutableMapOf<String, String>()
        // 数值字段解析错误无条件报出（默认值 "0"/"10000" 等均可正常解析）。
        if (dimension == null) errors["dimension"] = "向量维度必须为整数"
        if (timeoutMs == null) errors["timeoutMs"] = "超时（毫秒）必须为整数"
        if (maxRetries == null) errors["maxRetries"] = "重试次数必须为整数"
        if (batchSize == null) errors["batchSize"] = "批量大小必须为整数"
        if (errors.isNotEmpty()) {
            _state.update { it.copy(validationErrors = errors) }
            return null
        }

        val action = when {
            s.clearKeyRequested -> ApiKeyAction.Clear
            s.apiKeyDraft.isNotBlank() -> ApiKeyAction.Replace(s.apiKeyDraft)
            else -> ApiKeyAction.Keep
        }
        val config = EmbeddingConfig(
            providerType = "HTTP_COMPATIBLE",
            baseUrl = s.baseUrl,
            modelId = s.modelId.trim(),
            modelVersion = s.modelVersion.trim().takeIf { it.isNotEmpty() },
            dimension = dimension ?: 0,
            distanceMetric = DistanceMetric.COSINE,
            timeoutMs = timeoutMs ?: DEFAULT_TIMEOUT_MS,
            maxRetries = maxRetries ?: DEFAULT_MAX_RETRIES,
            batchSize = batchSize ?: DEFAULT_BATCH_SIZE,
            allowedHosts = s.allowedHosts.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        )
        return config to action
    }

    private fun describeTestResult(result: ConnectionTestResult): String = when (result) {
        is ConnectionTestResult.Success ->
            "连接测试成功（${result.testMethod ?: "embeddings"}，未保存，点击“保存”生效）"
        is ConnectionTestResult.NetworkError -> "连接测试失败：网络不可达（${result.detail}）"
        is ConnectionTestResult.Timeout -> "连接测试失败：超时"
        is ConnectionTestResult.Unauthorized -> "连接测试失败：鉴权失败（401/403）"
        is ConnectionTestResult.InvalidResponse ->
            "连接测试失败：${result.detail}${result.testMethod?.let { "（$it）" } ?: ""}"
    }

    private fun EmbeddingConfigUiState.recomputeDirty(): EmbeddingConfigUiState {
        val saved = savedConfig
        val dirty = saved == null ||
            baseUrl.trim() != saved.baseUrl.trim() ||
            modelId.trim() != saved.modelId.trim() ||
            modelVersion.trim() != (saved.modelVersion ?: "").trim() ||
            dimension.trim().toIntOrNull() != saved.dimension ||
            timeoutMs.trim().toLongOrNull() != saved.timeoutMs ||
            maxRetries.trim().toIntOrNull() != saved.maxRetries ||
            batchSize.trim().toIntOrNull() != saved.batchSize ||
            allowedHosts.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() } != saved.allowedHosts ||
            apiKeyDraft.isNotBlank() ||
            clearKeyRequested
        return copy(hasUnsavedChanges = dirty)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_BATCH_SIZE = 16
    }
}
