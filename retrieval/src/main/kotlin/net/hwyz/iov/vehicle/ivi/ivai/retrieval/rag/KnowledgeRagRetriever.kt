package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeChunk
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeEvidence
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorStore
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionConstraint

/**
 * L2 知识索引元数据键（CR-011）。KnowledgeChunk 与 Tool 文档不得写入同一逻辑
 * 索引或互相作为另一条路径的检索结果。
 */
object KnowledgeMetadataKeys {
    const val SOURCE_ID = "sourceId"
    const val SOURCE_TYPE = "sourceType"
    const val VEHICLE_MODELS = "vehicleModels"
    const val SOFTWARE_MIN = "softwareMin"
    const val SOFTWARE_MAX = "softwareMax"
    const val LANGUAGE = "language"
    const val CHUNK_ID = "chunkId"
}

/**
 * L2 Knowledge RAG 检索器（CR-011）。
 *
 * 查询流程：Normalized query → Knowledge scope / source / vehicle / software
 * version / language filter（先过滤再检索）→ EmbeddingProvider.embed(query) →
 * knowledge index exact cosine search → minScore + source diversity +
 * adjacent-chunk merge → Evidence Top-K → Reranker NONE → Local LLM grounded
 * context。
 *
 * 约束：只检索批准 Knowledge Corpus；不得把无来源模型记忆补充为证据。
 */
class KnowledgeRagRetriever(
    private val vectorStore: VectorStore,
    private val embeddingProvider: EmbeddingProvider,
    private val chunkResolver: (String) -> KnowledgeChunk?,
    private val namespace: String = DEFAULT_NAMESPACE,
    private val maxPerSource: Int = DEFAULT_MAX_PER_SOURCE
) : KnowledgeRetriever {

    override suspend fun retrieve(query: KnowledgeRetrievalQuery, topK: Int): List<KnowledgeEvidence> {
        if (!embeddingProvider.available) return emptyList()
        val response = embeddingProvider.embed(EmbeddingRequest(listOf(query.text)))
        if (response.vectors.isEmpty()) return emptyList()

        val hits = vectorStore.search(
            VectorQuery(
                namespace = namespace,
                vector = response.vectors.first(),
                topK = topK * SEARCH_MULTIPLIER,
                filter = { md -> metadataFilter(md, query) }
            )
        )
        val evidences = hits.mapNotNull { hit ->
            val chunk = chunkResolver(hit.documentId) ?: return@mapNotNull null
            KnowledgeEvidence(chunk = chunk, score = hit.score, matchedFields = listOf("vector"))
        }
        // source diversity + 相邻片段合并。
        val merged = mergeAdjacent(evidences)
        val diverse = sourceDiversity(merged, maxPerSource)
        return diverse.sortedByDescending { it.score }.take(topK)
    }

    /** 先过滤再检索：来源 / 车型 / 软件版本 / 语言。 */
    private fun metadataFilter(md: Map<String, String>, query: KnowledgeRetrievalQuery): Boolean {
        val sourceId = md[KnowledgeMetadataKeys.SOURCE_ID]
        if (query.sourceIds.isNotEmpty() && sourceId != null && sourceId !in query.sourceIds) return false
        if (query.vehicleModel != null) {
            val models = (md[KnowledgeMetadataKeys.VEHICLE_MODELS] ?: "").split(",").filter { it.isNotBlank() }
            if (models.isNotEmpty() && models.none { it == "*" || it == query.vehicleModel }) return false
        }
        if (query.softwareVersion != null && !versionCompatible(md, query.softwareVersion)) return false
        if (query.language != "*") {
            val lang = md[KnowledgeMetadataKeys.LANGUAGE]
            if (lang != null && lang != query.language) return false
        }
        return true
    }

    private fun versionCompatible(md: Map<String, String>, current: String): Boolean {
        val constraint = VersionConstraint(
            min = md[KnowledgeMetadataKeys.SOFTWARE_MIN]?.takeIf { it.isNotBlank() },
            max = md[KnowledgeMetadataKeys.SOFTWARE_MAX]?.takeIf { it.isNotBlank() }
        )
        val min = constraint.min
        val max = constraint.max
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

    /**
     * 相邻片段合并（CR-011）：同一来源、章节路径同父级的片段合并为一条证据，
     * 保留最高分与完整内容（证据上下文更完整）。
     */
    private fun mergeAdjacent(evidences: List<KnowledgeEvidence>): List<KnowledgeEvidence> {
        val bySource = evidences.groupBy { it.chunk.sourceId }
        return bySource.values.flatMap { group ->
            val siblings = group.groupBy { ev -> ev.chunk.sectionPath.substringBeforeLast('/', "") }
            siblings.values.map { siblingGroup ->
                if (siblingGroup.size <= 1) {
                    siblingGroup.single()
                } else {
                    val best = siblingGroup.maxByOrNull { it.score }!!
                    val mergedContent = siblingGroup
                        .sortedBy { it.chunk.chunkId }
                        .joinToString("\n") { it.chunk.content }
                    best.copy(
                        chunk = best.chunk.copy(content = mergedContent),
                        matchedFields = best.matchedFields + "adjacent-merged"
                    )
                }
            }
        }
    }

    /** 来源多样性：同一来源最多保留 [maxPerSource] 条证据。 */
    private fun sourceDiversity(evidences: List<KnowledgeEvidence>, maxPerSource: Int): List<KnowledgeEvidence> {
        val perSource = mutableMapOf<String, Int>()
        return evidences.sortedByDescending { it.score }.filter { ev ->
            val n = perSource.getOrDefault(ev.chunk.sourceId, 0)
            if (n < maxPerSource) {
                perSource[ev.chunk.sourceId] = n + 1
                true
            } else {
                false
            }
        }
    }

    companion object {
        const val DEFAULT_NAMESPACE = "knowledge"
        private const val DEFAULT_MAX_PER_SOURCE = 2
        private const val SEARCH_MULTIPLIER = 3
    }
}
