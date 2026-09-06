package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolCandidate
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolDefinitionSummary
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rerank.NoopRerankerProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rerank.RerankerProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rerank.RetrievalCandidate
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorStore
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolAvailabilityCheck
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition

/**
 * L1 Tool/Intent RAG 检索器（CR-011）。
 *
 * 查询流程：Normalized query → Domain / OperationType / Capability Pack /
 * Runtime Capability filter（先过滤再检索）→ EmbeddingProvider.embed(query) →
 * tool-intent index exact cosine search → minScore + canonical ID 去重 + 治理加权
 * → Tool Top-K → Reranker NONE → Local LLM candidate context。
 *
 * 安全约束：只检索统一运行时候选集的合法子集（[ToolRetrievalQuery.runtimeCapabilityToolIds]）；
 * L1 允许的确定性加权仅包括已治理的 Domain、OperationType、Alias 和槽位覆盖，
 * 不得恢复被治理状态或 Binding 过滤掉的资产。
 */
class ToolRagRetriever(
    private val registry: ToolRegistry,
    private val vectorStore: VectorStore,
    private val embeddingProvider: EmbeddingProvider,
    private val namespace: String = DEFAULT_NAMESPACE,
    private val reranker: RerankerProvider = NoopRerankerProvider(),
    private val boost: GovernedBoost = GovernedBoost()
) : ToolRetriever {

    override suspend fun retrieve(query: ToolRetrievalQuery, topK: Int): List<ToolCandidate> {
        if (!embeddingProvider.available) return emptyList()
        val response = embeddingProvider.embed(EmbeddingRequest(listOf(query.text)))
        if (response.vectors.isEmpty()) return emptyList()

        val hits = vectorStore.search(
            VectorQuery(
                namespace = namespace,
                vector = response.vectors.first(),
                topK = (topK * SEARCH_MULTIPLIER).coerceAtLeast(16),
                filter = { md -> metadataFilter(md, query) }
            )
        )

        // canonical ID 去重：同一资产（含 Alias 分片）只保留最高分。
        val bestByCanonical = mutableMapOf<String, Double>()
        for (hit in hits) {
            val canonicalId = hit.metadata[RetrievalMetadataKeys.CANONICAL_ID] ?: hit.documentId
            bestByCanonical[canonicalId] = maxOf(
                bestByCanonical[canonicalId] ?: Double.NEGATIVE_INFINITY,
                hit.score
            )
        }

        // 治理加权 + 可用性校验。
        val candidates = bestByCanonical.mapNotNull { (canonicalId, baseScore) ->
            val tool = registry.get(canonicalId) ?: return@mapNotNull null
            if (!ToolAvailabilityCheck.isAvailable(tool, query.vehicleModel, query.softwareVersion)) {
                return@mapNotNull null
            }
            ToolCandidate(
                toolId = canonicalId,
                score = boost.boost(tool, query, baseScore),
                matchedFields = listOf("vector"),
                definition = ToolDefinitionSummary.from(tool)
            )
        }.sortedByDescending { it.score }

        // RerankerProvider.NONE：保持基础顺序；将来引入 HTTP/LOCAL 只重排已过滤结果。
        val reranked = reranker.rerank(
            query.text,
            candidates.map { RetrievalCandidate(it.toolId, it.toolId, it.definition.description, it.score) }
        )
        val rerankedIds = reranked.map { it.documentId }.toSet()
        val ordered = candidates.filter { it.toolId in rerankedIds } +
            candidates.filter { it.toolId !in rerankedIds }
        return ordered.take(topK)
    }

    /** 先过滤再检索：Domain / OperationType / Capability Pack / Runtime Capability。 */
    private fun metadataFilter(md: Map<String, String>, query: ToolRetrievalQuery): Boolean {
        val domainIds = (md[RetrievalMetadataKeys.DOMAIN_IDS] ?: "").split(",").filter { it.isNotBlank() }
        if (query.domainIds.isNotEmpty() &&
            domainIds.none { it in query.domainIds.map { d -> d.code } }
        ) {
            return false
        }
        val operationTypes = (md[RetrievalMetadataKeys.OPERATION_TYPES] ?: "").split(",").filter { it.isNotBlank() }
        if (query.operationTypes.isNotEmpty() &&
            operationTypes.none { it in query.operationTypes.map { o -> o.name } }
        ) {
            return false
        }
        val packIds = (md[RetrievalMetadataKeys.CAPABILITY_PACK_IDS] ?: "").split(",").filter { it.isNotBlank() }
        if (query.capabilityPackIds.isNotEmpty() &&
            packIds.none { it in query.capabilityPackIds }
        ) {
            return false
        }
        val canonicalId = md[RetrievalMetadataKeys.CANONICAL_ID]
        if (query.runtimeCapabilityToolIds.isNotEmpty() &&
            canonicalId != null && canonicalId !in query.runtimeCapabilityToolIds
        ) {
            return false
        }
        return true
    }

    companion object {
        const val DEFAULT_NAMESPACE = "tool-intent"
        private const val SEARCH_MULTIPLIER = 3
    }
}

/**
 * 治理加权（CR-011）。仅基于已治理的 Domain、OperationType、Alias 与槽位覆盖，
 * 与 [ToolRetrievalQuery] 的过滤维度一致，保证不把被治理/过滤掉的资产捞回来。
 */
class GovernedBoost(
    private val domainWeight: Double = 0.25,
    private val operationTypeWeight: Double = 0.25,
    private val aliasWeight: Double = 0.5,
    private val slotCoverageWeight: Double = 0.3
) {

    fun boost(tool: ToolDefinition, query: ToolRetrievalQuery, baseScore: Double): Double {
        var delta = 0.0
        if (query.domainIds.isNotEmpty() && tool.domainId in query.domainIds) delta += domainWeight
        if (query.operationTypes.isNotEmpty() &&
            tool.supportedOperations.any { it in query.operationTypes }
        ) {
            delta += operationTypeWeight
        }
        val aliases = (tool.aliases.mapNotNull { it.aliasId } + listOfNotNull(tool.functionId)).toSet()
        if (aliases.any { query.text.contains(it) }) delta += aliasWeight
        val covered = tool.positiveExamples.count { query.text.contains(it) }
        if (covered > 0) delta += slotCoverageWeight * covered
        return baseScore + delta
    }
}
