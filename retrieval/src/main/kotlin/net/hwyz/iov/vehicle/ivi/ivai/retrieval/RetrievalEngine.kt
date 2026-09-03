package net.hwyz.iov.vehicle.ivi.ivai.retrieval

/**
 * Reserved retrieval contract for Tool/Intent RAG and Knowledge RAG
 * (IVI-IVAI-DSN-CR-001 后续演进 #1/#2).
 */
interface RetrievalEngine {
    /** Returns top-k candidate tool ids for a query (empty when no retrieval wired). */
    suspend fun topKTools(query: String, k: Int): List<String>
}
