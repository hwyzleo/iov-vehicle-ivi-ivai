package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagExecutionSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolCandidate
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolDefinitionSummary
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolAvailabilityCheck

/**
 * Source of the tool candidate set fed to the L1 prompt (CR-005). Returns the
 * SAME [ToolCandidateSet] whether RAG is on (retrieved Top-K) or off (all
 * enabled controlled tools), so Prompt Builder and the local LLM flow are
 * unchanged.
 */
interface ToolCandidateProvider {
    suspend fun candidates(
        input: NormalizedInput,
        context: AgentContext,
        ragSnapshot: RagExecutionSnapshot
    ): ToolCandidateSet
}

/** The candidate set type shared by both providers. */
data class ToolCandidateSet(
    val candidates: List<ToolCandidate>,
    val source: CandidateSetSource,
    val topK: Int,
    val retrieverType: String? = null,
    val fallbackReason: String? = null
)

enum class CandidateSetSource { ALL_ENABLED, RETRIEVED }

/**
 * RAG-off / degraded path (CR-005): returns the current vehicle's enabled
 * controlled tools as fixed candidates. MUST NOT touch Embedding / VectorIndex
 * / ToolRetriever. The name avoids implying RAG.
 */
class AllEnabledToolsProvider(
    private val registry: ToolRegistry
) : ToolCandidateProvider {

    override suspend fun candidates(
        input: NormalizedInput,
        context: AgentContext,
        ragSnapshot: RagExecutionSnapshot
    ): ToolCandidateSet {
        val candidates = registry.all()
            .filter { ToolAvailabilityCheck.isAvailable(it, context.vehicleModel, context.softwareVersion) }
            .sortedByDescending { it.selectionPriority }
            .map { ToolCandidate(it.toolId, 1.0, listOf("enabled"), ToolDefinitionSummary.from(it)) }
        return ToolCandidateSet(
            candidates = candidates,
            source = CandidateSetSource.ALL_ENABLED,
            topK = candidates.size,
            fallbackReason = null
        )
    }
}

/**
 * RAG-on path (CR-005): runs the [ToolRetriever] and returns Top-K. When the
 * retrieval is empty / below threshold it falls back to the fixed candidates
 * (Tool RAG 不可用时回退固定候选), recording the fallback reason. Every
 * recalled tool id is whitelisted against the registry before it may appear in
 * a prompt.
 */
class RagToolCandidateProvider(
    private val registry: ToolRegistry,
    private val toolRetriever: ToolRetriever
) : ToolCandidateProvider {

    override suspend fun candidates(
        input: NormalizedInput,
        context: AgentContext,
        ragSnapshot: RagExecutionSnapshot
    ): ToolCandidateSet {
        // RAG 关闭/不可用时：固定受控候选，绝不触碰 Embedding/VectorIndex/Retriever。
        if (!ragSnapshot.toolRagAvailable) {
            return AllEnabledToolsProvider(registry).candidates(input, context, ragSnapshot)
        }
        val query = ToolRetrievalQuery(
            text = input.normalized,
            vehicleModel = context.vehicleModel,
            softwareVersion = context.softwareVersion
        )
        val retrieved = toolRetriever.retrieve(query, ragSnapshot.toolTopK)
            .filter { registry.get(it.toolId) != null }
        if (retrieved.isEmpty()) {
            return AllEnabledToolsProvider(registry).candidates(input, context, ragSnapshot)
                .copy(fallbackReason = "retrieval_empty_fallback")
        }
        return ToolCandidateSet(
            candidates = retrieved,
            source = CandidateSetSource.RETRIEVED,
            topK = ragSnapshot.toolTopK,
            retrieverType = toolRetriever::class.simpleName,
            fallbackReason = null
        )
    }
}
