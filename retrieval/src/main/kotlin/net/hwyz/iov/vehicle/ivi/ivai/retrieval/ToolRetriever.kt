package net.hwyz.iov.vehicle.ivi.ivai.retrieval

/**
 * Tool/Intent RAG contract (IVI-IVAI-DSN-CR-005). A [ToolRetriever] recalls
 * candidate tools from a large tool set; it never decides the final intent and
 * never executes a tool. The local LLM selects / extracts arguments / asks or
 * rejects strictly within the recalled Top-K.
 */
interface ToolRetriever {
    suspend fun retrieve(query: ToolRetrievalQuery, topK: Int): List<ToolCandidate>
}
