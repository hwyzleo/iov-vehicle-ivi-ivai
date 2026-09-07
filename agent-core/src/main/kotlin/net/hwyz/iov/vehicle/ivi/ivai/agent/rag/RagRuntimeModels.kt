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
 * Per-turn RAG execution info attached to observability / UI details (CR-005,
 * extended CR-011). [retrievalExecuted] is set only when a retriever actually
 * ran.
 *
 * CR-011 可观测性记录项：ragEnabled、indexId、indexVersion、modelId、
 * modelVersion、documentCount、eligibleCandidateCount、filteredCandidateCount、
 * topK、topScores、selectedCanonicalIds、embeddingLatencyMs、searchLatencyMs、
 * fallbackReason。不得记录 API Key、完整 Authorization Header、未脱敏请求体或
 * 不必要的用户原文。
 */
@Serializable
data class RagExecutionInfo(
    val configuredEnabled: Boolean,
    val runtimeStatus: RagRuntimeStatus,
    val retrievalExecuted: Boolean,
    val retrievalType: String? = null,
    val indexVersion: String? = null,
    val fallbackReason: String? = null,
    // ---- CR-011 可观测性 ----
    val ragEnabled: Boolean = configuredEnabled,
    val indexId: String? = null,
    val modelId: String? = null,
    val modelVersion: String? = null,
    val documentCount: Int? = null,
    val eligibleCandidateCount: Int? = null,
    val filteredCandidateCount: Int? = null,
    val topK: Int? = null,
    val topScores: List<Double> = emptyList(),
    val selectedCanonicalIds: List<String> = emptyList(),
    /** 检索输入（L1 归一化文本 / L2 知识查询），仅调试面板展示。 */
    val queryText: String? = null,
    /** 检索输出条目（L1 工具名 / L2 知识片段标题），仅调试面板展示。 */
    val retrievedTitles: List<String> = emptyList(),
    val embeddingLatencyMs: Long? = null,
    val searchLatencyMs: Long? = null
)
