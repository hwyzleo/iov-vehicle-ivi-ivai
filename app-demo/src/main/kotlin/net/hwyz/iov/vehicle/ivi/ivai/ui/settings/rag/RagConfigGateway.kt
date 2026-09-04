package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.rag

import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagSaveResult

/**
 * RAG settings gateway (CR-005): exposes the persisted config + runtime health
 * from the agent service and saves user edits. API Key 等模型密钥不属于 RAG 配置。
 */
interface RagConfigGateway {
    fun runtimeStatus(): RagRuntimeStatus
    suspend fun load(): RagRuntimeConfig
    suspend fun save(draft: RagConfigDraft): RagSaveResult
    suspend fun resetToDefault(): RagSaveResult
}

/** RAG settings screen state (CR-005). */
data class RagConfigUiState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val toolRagEnabled: Boolean = true,
    val knowledgeRagEnabled: Boolean = true,
    val toolTopK: Int = 5,
    val knowledgeTopK: Int = 5,
    val runtimeStatus: String = RagRuntimeStatus.DISABLED.name,
    val saving: Boolean = false,
    val message: String? = null
)
