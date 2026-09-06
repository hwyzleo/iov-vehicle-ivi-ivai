package net.hwyz.iov.vehicle.ivi.ivai.retrieval.tool

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolCandidate
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolDefinitionSummary
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.Tokenizer
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolAvailabilityCheck
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId

/**
 * Hybrid rule/keyword retriever (CR-005 first phase): scores tools by
 * positive-example / synonym / name substring hits plus a lightweight
 * description bigram overlap (BM25-ish). Applies vehicle / software filtering,
 * a minimum score threshold and Top-K truncation. Intentionally does NOT decide
 * the final intent — the local LLM selects within the recalled Top-K.
 *
 * CR-008: when [ToolRetrievalQuery.capabilityPackIds] / [domainIds] are set,
 * the candidate space is filtered BEFORE scoring (先过滤再检索) — tools outside
 * the selected Capability Packs / business domains never enter the recall.
 */
class HybridRuleToolRetriever(
    private val registry: ToolRegistry,
    private val tokenizer: Tokenizer = Tokenizer
) : ToolRetriever {

    override suspend fun retrieve(query: ToolRetrievalQuery, topK: Int): List<ToolCandidate> {
        val q = query.text
        val queryTokens = tokenizer.tokenize(q).toSet()
        return registry.all()
            .filter { ToolAvailabilityCheck.isAvailable(it, query.vehicleModel, query.softwareVersion) }
            .filter { it.toolId !in query.excludeToolIds }
            .filter { packAllowed(it.capabilityPackId, query.capabilityPackIds) }
            .filter { domainAllowed(it.domainId, query.domainIds) }
            .map { tool ->
                val summary = ToolDefinitionSummary.from(tool)
                val score = score(summary, q, queryTokens)
                ToolCandidate(tool.toolId, score, matchedFields(summary, q), summary)
            }
            .filter { it.score >= MIN_SCORE }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun score(summary: ToolDefinitionSummary, q: String, queryTokens: Set<String>): Double {
        var score = 0.0
        for (example in summary.positiveExamples) if (q.contains(example)) score += POSITIVE_HIT
        for (synonym in summary.synonyms) if (q.contains(synonym)) score += SYNONYM_HIT
        if (q.contains(summary.name)) score += NAME_HIT
        for (negative in summary.negativeExamples) if (q.contains(negative)) score -= NEGATIVE_HIT
        val descriptionTokens = tokenizer.tokenize("${summary.name} ${summary.description}").toSet()
        score += (queryTokens intersect descriptionTokens).size * DESCRIPTION_TERM_WEIGHT
        return score
    }

    private fun matchedFields(summary: ToolDefinitionSummary, q: String): List<String> {
        val fields = mutableListOf<String>()
        if (summary.positiveExamples.any { q.contains(it) }) fields += "positiveExamples"
        if (summary.synonyms.any { q.contains(it) }) fields += "synonyms"
        if (q.contains(summary.name)) fields += "name"
        val descriptionTokens = tokenizer.tokenize(summary.description).toSet()
        if (descriptionTokens.isNotEmpty() && descriptionTokens.any { q.contains(it) }) fields += "description"
        return fields
    }

    private fun packAllowed(packId: String, allowed: List<String>): Boolean =
        allowed.isEmpty() || packId in allowed

    private fun domainAllowed(domain: BusinessDomainId, allowed: List<BusinessDomainId>): Boolean =
        allowed.isEmpty() || domain in allowed

    private companion object {
        const val MIN_SCORE = 0.5
        const val POSITIVE_HIT = 2.0
        const val SYNONYM_HIT = 2.0
        const val NAME_HIT = 1.5
        const val NEGATIVE_HIT = 1.0
        const val DESCRIPTION_TERM_WEIGHT = 0.5
    }
}
