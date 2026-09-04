package net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeChunk
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.Tokenizer

/**
 * In-memory keyword KnowledgeRetriever over a provided chunk set (CR-005).
 * Applies vehicle / software / language filtering, title & section boosted
 * bigram scoring and Top-K truncation. A real deployment swaps the source for
 * a [net.hwyz.iov.vehicle.ivi.ivai.retrieval.index.VectorIndex] + embedding;
 * the workflow contract is unchanged.
 */
class KnowledgeRetrieverImpl(
    private val chunks: List<KnowledgeChunk>,
    private val tokenizer: Tokenizer = Tokenizer
) : KnowledgeRetriever {

    override suspend fun retrieve(query: KnowledgeRetrievalQuery, topK: Int): List<KnowledgeChunk> {
        val queryTokens = tokenizer.tokenize(query.text).toSet()
        return chunks
            .filter { chunkMatchesVehicle(it, query) }
            .map { chunk -> chunk.copy(score = score(chunk, query.text, queryTokens)) }
            .filter { it.score >= MIN_SCORE }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun chunkMatchesVehicle(chunk: KnowledgeChunk, query: KnowledgeRetrievalQuery): Boolean {
        if (query.vehicleModel != null && chunk.vehicleModels.isNotEmpty()) {
            val compatible = chunk.vehicleModels.any { it == "*" || it == query.vehicleModel }
            if (!compatible) return false
        }
        if (query.language != chunk.language) return false
        return true
    }

    private fun score(chunk: KnowledgeChunk, query: String, queryTokens: Set<String>): Double {
        var score = 0.0
        val titleTokens = tokenizer.tokenize(chunk.title + chunk.sectionPath.joinToString("")).toSet()
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
