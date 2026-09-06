package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import kotlinx.serialization.Serializable
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagConfig

/**
 * Persistent RAG runtime configuration (IVI-IVAI-DSN-CR-005 + CR-011).
 * Belongs to the Agent/Retrieval runtime config — never to an Ollama / OpenAI
 * ModelProvider private config.
 *
 * [enabled] is the master switch and defaults OFF so the app starts fine
 * without any vector index or embedding model. [toolRagEnabled] /
 * [knowledgeRagEnabled] only take effect when [enabled] is true, letting Tool
 * and Knowledge indexes deploy independently.
 *
 * CR-011 兼容策略：v1 字段（[enabled]/[toolRagEnabled]/[knowledgeRagEnabled]/
 * [toolTopK]/[knowledgeTopK]）保持为设置 UI 的主开关与 Top-K 输入；新增
 * [rag]（RagConfig）承载 Embedding / VectorStore / 双路径 / Reranker 高级配置。
 * [effectiveRagConfig] 将 UI 开关镜像进 CR-011 配置模型，保证 UI 兼容
 * （老用户已存配置平滑升级，设置界面无需重写）。
 */
@Serializable
data class RagRuntimeConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val enabled: Boolean = false,
    val toolRagEnabled: Boolean = true,
    val knowledgeRagEnabled: Boolean = true,
    val toolTopK: Int = DEFAULT_TOOL_TOP_K,
    val knowledgeTopK: Int = DEFAULT_KNOWLEDGE_TOP_K,
    val version: Long = 0L,
    /** CR-011 高级配置（Embedding / VectorStore / 双路径 / Reranker）。 */
    val rag: RagConfig = RagConfig()
) {
    /**
     * CR-011 生效配置：UI 开关与 Top-K 镜像进 [RagConfig]，高级配置保留。
     * L1 默认 topK=10、注入候选 ≤5；L2 Top-K 独立配置。
     */
    fun effectiveRagConfig(): RagConfig = rag.copy(
        enabled = enabled,
        toolRetrieval = rag.toolRetrieval.copy(
            enabled = toolRagEnabled,
            topK = toolTopK
        ),
        knowledgeRetrieval = rag.knowledgeRetrieval.copy(
            enabled = knowledgeRagEnabled,
            topK = knowledgeTopK
        )
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_TOOL_TOP_K = 5
        const val DEFAULT_KNOWLEDGE_TOP_K = 5
    }
}

/**
 * User-editable draft from the "检索增强" settings group. Top-K values use the
 * defaults unless explicitly changed (advanced parameters are not required to
 * be exposed to ordinary users in the first phase — CR-011 高级配置随 [rag]
 * 透传，默认不强制上 UI)。
 */
data class RagConfigDraft(
    val enabled: Boolean = false,
    val toolRagEnabled: Boolean = true,
    val knowledgeRagEnabled: Boolean = true,
    val toolTopK: Int = RagRuntimeConfig.DEFAULT_TOOL_TOP_K,
    val knowledgeTopK: Int = RagRuntimeConfig.DEFAULT_KNOWLEDGE_TOP_K,
    val rag: RagConfig = RagConfig()
)
