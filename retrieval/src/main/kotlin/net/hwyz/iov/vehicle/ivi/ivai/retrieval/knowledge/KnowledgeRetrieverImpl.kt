package net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeChunk
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeEvidence
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.Tokenizer

/**
 * In-memory keyword KnowledgeRetriever over a provided chunk set (CR-005,
 * evolved in CR-011 to return [KnowledgeEvidence]). Applies vehicle / software
 * version / language filtering, title & section boosted bigram scoring and
 * Top-K truncation. A real deployment swaps the source for the vector path
 * ([KnowledgeRagRetriever] + [VectorStore]); the workflow contract is unchanged.
 */
class KnowledgeRetrieverImpl(
    private val chunks: List<KnowledgeChunk>,
    private val tokenizer: Tokenizer = Tokenizer
) : KnowledgeRetriever {

    override suspend fun retrieve(query: KnowledgeRetrievalQuery, topK: Int): List<KnowledgeEvidence> {
        val queryTokens = tokenizer.tokenize(query.text).toSet()
        return chunks
            .filter { chunkMatchesScope(it, query) }
            .map { chunk ->
                KnowledgeEvidence(
                    chunk = chunk,
                    score = score(chunk, query.text, queryTokens),
                    matchedFields = listOf("keyword")
                )
            }
            .filter { it.score >= MIN_SCORE }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun chunkMatchesScope(chunk: KnowledgeChunk, query: KnowledgeRetrievalQuery): Boolean {
        if (query.sourceIds.isNotEmpty() && chunk.sourceId !in query.sourceIds) return false
        if (query.vehicleModel != null && chunk.vehicleModels.isNotEmpty()) {
            val compatible = chunk.vehicleModels.any { it == "*" || it == query.vehicleModel }
            if (!compatible) return false
        }
        if (query.softwareVersion != null && !versionCompatible(chunk, query.softwareVersion)) return false
        if (query.language != chunk.language) return false
        return true
    }

    private fun versionCompatible(chunk: KnowledgeChunk, current: String): Boolean {
        val c = chunk.softwareVersions
        val min = c.min
        val max = c.max
        val minOk = min == null || compare(current, min) >= 0
        val maxOk = max == null || compare(current, max) < 0
        return minOk && maxOk
    }

    private fun compare(a: String, b: String): Int {
        val sa = a.trim().split(".").mapNotNull { it.toIntOrNull() }
        val sb = b.trim().split(".").mapNotNull { it.toIntOrNull() }
        val max = maxOf(sa.size, sb.size)
        for (i in 0 until max) {
            val va = sa.getOrElse(i) { 0 }
            val vb = sb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    private fun score(chunk: KnowledgeChunk, query: String, queryTokens: Set<String>): Double {
        var score = 0.0
        val titleTokens = tokenizer.tokenize(chunk.title + chunk.sectionPath.replace("/", "")).toSet()
        score += (queryTokens intersect titleTokens).size * TITLE_TERM_WEIGHT
        val contentTokens = tokenizer.tokenize(chunk.content).toSet()
        score += (queryTokens intersect contentTokens).size * CONTENT_TERM_WEIGHT
        if (chunk.content.contains(query) || chunk.title.contains(query)) score += EXACT_HIT
        return score
    }

    private companion object {
        const val MIN_SCORE = 0.4
        const val TITLE_TERM_WEIGHT = 1.0
        const val CONTENT_TERM_WEIGHT = 0.5
        const val EXACT_HIT = 2.0
    }
}
