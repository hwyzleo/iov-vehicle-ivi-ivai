package net.hwyz.iov.vehicle.ivi.ivai.retrieval.tool

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolCandidate
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolDefinitionSummary
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.index.VectorIndex
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolAvailabilityCheck

/**
 * Vector tool retriever (CR-005 post-first-phase): retrieves Top-K tools by
 * embedding + [VectorIndex] search. Plugged in when the tool count / expression
 * diversity grows; the Agent workflow contract is unchanged. The index document
 * ids map to tool ids via [idToToolId].
 */
class VectorToolRetriever(
    private val registry: ToolRegistry,
    private val vectorIndex: VectorIndex,
    private val embeddingProvider: EmbeddingProvider,
    private val idToToolId: Map<String, String>
) : ToolRetriever {

    override suspend fun retrieve(query: ToolRetrievalQuery, topK: Int): List<ToolCandidate> {
        if (!embeddingProvider.available) return emptyList()
        val vectors = embeddingProvider.embed(listOf(query.text))
        if (vectors.isEmpty()) return emptyList()
        val hits = vectorIndex.search(vectors.first(), topK)
        return hits.mapNotNull { hit ->
            val toolId = idToToolId[hit.id] ?: return@mapNotNull null
            val tool = registry.get(toolId) ?: return@mapNotNull null
            if (!ToolAvailabilityCheck.isAvailable(tool, query.vehicleModel, query.softwareVersion)) {
                return@mapNotNull null
            }
            ToolCandidate(toolId, hit.score, listOf("vector"), ToolDefinitionSummary.from(tool))
        }
    }
}
