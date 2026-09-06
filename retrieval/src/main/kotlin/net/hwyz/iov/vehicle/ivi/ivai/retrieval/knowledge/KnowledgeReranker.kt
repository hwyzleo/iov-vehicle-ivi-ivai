package net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeEvidence

/**
 * Re-ranking + threshold gate over recalled knowledge evidence (CR-005, evolved
 * in CR-011 to operate on [KnowledgeEvidence]): drops low-score chunks,
 * preserves order and truncates to Top-K. "无证据/冲突" is decided downstream
 * by the LLM from the surviving chunks.
 */
class KnowledgeReranker(
    private val minScore: Double = DEFAULT_MIN_SCORE
) {

    fun rerank(evidences: List<KnowledgeEvidence>, topK: Int): List<KnowledgeEvidence> =
        evidences
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)

    private companion object {
        const val DEFAULT_MIN_SCORE = 0.3
    }
}
