package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import kotlinx.serialization.Serializable

/**
 * Persistent RAG runtime configuration (IVI-IVAI-DSN-CR-005). Belongs to the
 * Agent/Retrieval runtime config — never to an Ollama / OpenAI ModelProvider
 * private config.
 *
 * [enabled] is the master switch and defaults OFF so the app starts fine
 * without any vector index or embedding model. [toolRagEnabled] /
 * [knowledgeRagEnabled] only take effect when [enabled] is true, letting Tool
 * and Knowledge indexes deploy independently.
 */
@Serializable
data class RagRuntimeConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val enabled: Boolean = false,
    val toolRagEnabled: Boolean = true,
    val knowledgeRagEnabled: Boolean = true,
    val toolTopK: Int = DEFAULT_TOOL_TOP_K,
    val knowledgeTopK: Int = DEFAULT_KNOWLEDGE_TOP_K,
    val version: Long = 0L
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_TOOL_TOP_K = 5
        const val DEFAULT_KNOWLEDGE_TOP_K = 5
    }
}

/**
 * User-editable draft from the "检索增强" settings group. Top-K values use the
 * defaults unless explicitly changed (advanced parameters are not required to
 * be exposed to ordinary users in the first phase).
 */
data class RagConfigDraft(
    val enabled: Boolean = false,
    val toolRagEnabled: Boolean = true,
    val knowledgeRagEnabled: Boolean = true,
    val toolTopK: Int = RagRuntimeConfig.DEFAULT_TOOL_TOP_K,
    val knowledgeTopK: Int = RagRuntimeConfig.DEFAULT_KNOWLEDGE_TOP_K
)
