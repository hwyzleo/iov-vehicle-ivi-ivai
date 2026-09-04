package net.hwyz.iov.vehicle.ivi.ivai.retrieval.tool

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolCandidate
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolDefinitionSummary
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetriever
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolAvailabilityCheck

/**
 * Fixed candidate retriever (CR-005 first phase): returns all tools enabled for
 * the current vehicle / software version, ordered by selection priority — no
 * relevance scoring. Used as the degraded/fallback path and for the initial 6
 * tools before a vector index is deployed.
 */
class FixedToolRetriever(
    private val registry: ToolRegistry
) : ToolRetriever {

    override suspend fun retrieve(query: ToolRetrievalQuery, topK: Int): List<ToolCandidate> =
        registry.all()
            .filter { ToolAvailabilityCheck.isAvailable(it, query.vehicleModel, query.softwareVersion) }
            .filter { it.toolId !in query.excludeToolIds }
            .sortedByDescending { it.selectionPriority }
            .take(topK)
            .map { ToolCandidate(it.toolId, 1.0, listOf("enabled"), ToolDefinitionSummary.from(it)) }
}
