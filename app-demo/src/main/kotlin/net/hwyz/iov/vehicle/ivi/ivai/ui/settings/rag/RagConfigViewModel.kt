package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.rag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.EmbeddingConfigValidator
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagSaveResult
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagConfig

/**
 * RAG settings ViewModel (CR-005). Reads the persisted config + runtime status
 * and persists user edits; every save affects only NEW requests (in-flight
 * requests keep their original snapshot).
 */
class RagConfigViewModel : ViewModel() {

    private val _state = MutableStateFlow(RagConfigUiState())
    val state: StateFlow<RagConfigUiState> = _state.asStateFlow()

    private var gateway: RagConfigGateway? = null

    /** 最近加载的 CR-011 高级配置（Embedding 等），开关保存时透传，避免误清空。 */
    private var currentRag: RagConfig = RagConfig()

    fun attach(gateway: RagConfigGateway) {
        this.gateway = gateway
        viewModelScope.launch {
            val config = runCatching { gateway.load() }.getOrNull()
            if (config == null) {
                _state.update { it.copy(message = "加载 RAG 配置失败") }
                return@launch
            }
            currentRag = config.rag
            _state.update {
                it.copy(
                    loaded = true,
                    enabled = config.enabled,
                    toolRagEnabled = config.toolRagEnabled,
                    knowledgeRagEnabled = config.knowledgeRagEnabled,
                    toolTopK = config.toolTopK,
                    knowledgeTopK = config.knowledgeTopK,
                    runtimeStatus = gateway.runtimeStatus().name,
                    embeddingSummary = embeddingSummary(config.rag.embedding),
                    message = null
                )
            }
        }
    }

    fun setEnabled(enabled: Boolean) = persist { it.copy(enabled = enabled) }

    fun setToolRagEnabled(enabled: Boolean) = persist { it.copy(toolRagEnabled = enabled) }

    fun setKnowledgeRagEnabled(enabled: Boolean) = persist { it.copy(knowledgeRagEnabled = enabled) }

    fun resetToDefault() {
        val g = gateway ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val result = g.resetToDefault()
            handleResult(result, "已恢复默认（RAG 关闭）")
        }
    }

    private fun persist(transform: (RagConfigUiState) -> RagConfigUiState) {
        val g = gateway ?: return
        _state.update { transform(it).copy(saving = true) }
        viewModelScope.launch {
            val s = _state.value
            val result = g.save(
                RagConfigDraft(
                    enabled = s.enabled,
                    toolRagEnabled = s.toolRagEnabled,
                    knowledgeRagEnabled = s.knowledgeRagEnabled,
                    toolTopK = s.toolTopK,
                    knowledgeTopK = s.knowledgeTopK,
                    rag = currentRag
                )
            )
            handleResult(result, "RAG 配置已保存")
        }
    }

    private fun handleResult(result: RagSaveResult, successMessage: String) {
        _state.update { st ->
            when (result) {
                is RagSaveResult.Success -> st.copy(
                    saving = false,
                    message = successMessage,
                    runtimeStatus = gateway?.runtimeStatus()?.name ?: st.runtimeStatus
                )
                is RagSaveResult.Failure -> st.copy(
                    saving = false,
                    message = result.message
                )
            }
        }
    }

    private fun embeddingSummary(embedding: net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig): String =
        if (EmbeddingConfigValidator.isBlank(embedding)) {
            "嵌入模型：本地默认（哈希桩）"
        } else {
            "嵌入模型：${embedding.providerType} · ${embedding.modelId} · dim=${embedding.dimension}"
        }
}
