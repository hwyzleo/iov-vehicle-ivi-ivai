package net.hwyz.iov.vehicle.ivi.ivai.retrieval

/**
 * Knowledge RAG contract (IVI-IVAI-DSN-CR-005). Retrieves Top-K knowledge
 * chunks for L2: the local LLM answers strictly from the provided chunks.
 */
interface KnowledgeRetriever {
    suspend fun retrieve(query: KnowledgeRetrievalQuery, topK: Int): List<KnowledgeChunk>
}
