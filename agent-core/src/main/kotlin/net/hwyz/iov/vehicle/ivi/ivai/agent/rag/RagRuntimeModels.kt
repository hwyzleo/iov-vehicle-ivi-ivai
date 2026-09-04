package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import kotlinx.serialization.Serializable

/**
 * Runtime health status of the RAG stack (CR-005). "开关打开" != "运行时可用":
 * only when the index, embedding model, version and integrity checks all pass
 * does the status become READY. A failure must NOT auto-turn the switch off —
 * the user's "已打开" is preserved while the runtime marks DEGRADED/UNAVAILABLE.
 */
enum class RagRuntimeStatus {
    DISABLED,
    INITIALIZING,
    READY,
    DEGRADED,
    UNAVAILABLE
}

/**
 * Immutable RAG execution snapshot captured once per request (CR-005). After a
 * save the NEW requests read the new snapshot; in-flight requests keep the old
 * one, so retrievers are never stopped/started mid-turn.
 */
data class RagExecutionSnapshot(
    val configVersion: Long,
    val enabled: Boolean,
    val toolRagAvailable: Boolean,
    val knowledgeRagAvailable: Boolean,
    val toolRetrieverType: String?,
    val knowledgeRetrieverType: String?,
    val toolTopK: Int = RagRuntimeConfig.DEFAULT_TOOL_TOP_K,
    val knowledgeTopK: Int = RagRuntimeConfig.DEFAULT_KNOWLEDGE_TOP_K
) {
    companion object {
        val DEFAULT = RagExecutionSnapshot(
            configVersion = 0L,
            enabled = false,
            toolRagAvailable = false,
            knowledgeRagAvailable = false,
            toolRetrieverType = null,
            knowledgeRetrieverType = null
        )
    }
}

/**
 * Per-turn RAG execution info attached to observability / UI details (CR-005).
 * [retrievalExecuted] is set only when a retriever actually ran.
 */
@Serializable
data class RagExecutionInfo(
    val configuredEnabled: Boolean,
    val runtimeStatus: RagRuntimeStatus,
    val retrievalExecuted: Boolean,
    val retrievalType: String? = null,
    val indexVersion: String? = null,
    val fallbackReason: String? = null
)
