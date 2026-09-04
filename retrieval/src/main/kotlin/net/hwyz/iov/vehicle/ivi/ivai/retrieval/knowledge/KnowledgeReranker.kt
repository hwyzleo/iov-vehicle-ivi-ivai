package net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeChunk

/**
 * Re-ranking + threshold gate over recalled knowledge chunks (CR-005): drops
 * low-score chunks, preserves order and truncates to Top-K. "无证据/冲突" is
 * decided downstream by the LLM from the surviving chunks.
 */
class KnowledgeReranker(
    private val minScore: Double = DEFAULT_MIN_SCORE
) {

    fun rerank(chunks: List<KnowledgeChunk>, topK: Int): List<KnowledgeChunk> =
        chunks
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)

    private companion object {
        const val DEFAULT_MIN_SCORE = 0.3
    }
}
