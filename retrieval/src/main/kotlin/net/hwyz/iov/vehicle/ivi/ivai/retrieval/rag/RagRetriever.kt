package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeEvidence
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.RetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolCandidate

/**
 * 检索结果（CR-011）。L1 返回 Tool/Workflow 候选；L2 返回 Knowledge 证据。
 */
sealed interface RagRetrievalResult {
    data class Tools(val candidates: List<ToolCandidate>) : RagRetrievalResult
    data class Knowledge(val evidence: List<KnowledgeEvidence>) : RagRetrievalResult
    data object Empty : RagRetrievalResult
}

/**
 * 统一 RAG 检索入口（CR-011）。
 *
 * 上层 Agent 只依赖本接口；实现内部依赖 [EmbeddingProvider] /
 * [VectorStore] / [RerankerProvider] 接口，不依赖具体存储或重排实现。
 * L1 / L2 使用独立命名空间与开关，可独立启停与重建。
 */
interface RagRetriever {
    suspend fun retrieve(query: RetrievalQuery): RagRetrievalResult
}
