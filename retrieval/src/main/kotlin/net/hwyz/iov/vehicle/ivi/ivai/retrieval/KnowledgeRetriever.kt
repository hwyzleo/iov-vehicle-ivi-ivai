package net.hwyz.iov.vehicle.ivi.ivai.retrieval

/**
 * Knowledge RAG contract (IVI-IVAI-DSN-CR-005 + CR-011). Retrieves Top-K
 * knowledge evidence for L2: the local LLM answers strictly from the provided
 * chunks. CR-011 返回 [KnowledgeEvidence]（片段 + 分数 + 命中字段）。
 */
interface KnowledgeRetriever {
    suspend fun retrieve(query: KnowledgeRetrievalQuery, topK: Int): List<KnowledgeEvidence>
}
