package net.hwyz.iov.vehicle.ivi.ivai.retrieval

/**
 * Default no-op retrieval used until Tool/Intent RAG is implemented.
 */
class EmptyRetrievalEngine : RetrievalEngine {
    override suspend fun topKTools(query: String, k: Int): List<String> = emptyList()
}
