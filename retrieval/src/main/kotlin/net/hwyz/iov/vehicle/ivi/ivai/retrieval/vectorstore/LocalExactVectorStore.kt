package net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagException
import kotlin.math.sqrt

/**
 * LOCAL_EXACT 向量存储（CR-011）。
 *
 * 在内存中保存归一化向量 + 治理元数据，执行精确检索；SQLite/文件持久化由
 * [VectorPersistence] 负责（staging → 原子发布 → 保留 previous 回滚）。
 * 查询时对候选子集执行点积（归一化后点积 ≡ 余弦相似度），符合
 * "先过滤再检索"的查询流程。
 *
 * 并发语义：active index 使用只读快照；构建在 staging 完成后原子切换，查询
 * 不读取半成品。同一兼容键只允许一个构建任务（并发互斥由 IndexLifecycle 协调）。
 */
class LocalExactVectorStore(
    private val persistence: VectorPersistence
) : VectorStore {

    /** namespace → active 只读快照（惰性从持久化加载）。 */
    private val activeIndexes = mutableMapOf<String, ActiveIndex>()

    override suspend fun open(manifest: VectorIndexManifest): VectorIndexHandle {
        val active = activeIndex(manifest.namespace)
            ?: throw RagException(
                RagErrorCode.NO_INDEX,
                "无可用索引: namespace=${manifest.namespace}"
            )
        requireCompatible(active.manifest, manifest)
        return LocalIndexHandle(active)
    }

    override suspend fun build(input: VectorIndexBuildInput): VectorIndexManifest {
        validateVectors(input)
        val normalized = normalize(input.vectors, input.distanceMetric)
        val manifest = VectorIndexManifest(
            indexId = "${input.namespace}-${input.indexVersion}",
            indexVersion = input.indexVersion,
            namespace = input.namespace,
            governanceVersion = input.governanceVersion,
            embeddingProviderType = input.providerType,
            modelId = input.modelId,
            modelVersion = input.modelVersion,
            dimension = input.dimension,
            distanceMetric = input.distanceMetric,
            documentBuilderVersion = input.documentBuilderVersion,
            documentCount = input.documents.size,
            contentSetHash = input.contentSetHash,
            createdAtEpochMillis = System.currentTimeMillis()
        )
        persistence.writeBuildState(input.namespace, IndexBuildState.BUILDING)
        persistence.writeStaging(input.namespace, manifest, input.documents, normalized)
        // 完整性 + 检索冒烟测试。
        val smokeOk = smokeTest(input.namespace, manifest, input.documents, normalized)
        if (!smokeOk) {
            persistence.deleteStaging(input.namespace)
            persistence.writeBuildState(input.namespace, IndexBuildState.FAILED)
            throw RagException(
                RagErrorCode.INTEGRITY_FAILED,
                "索引完整性/冒烟校验失败: namespace=${input.namespace}"
            )
        }
        persistence.publish(input.namespace)
        activeIndexes[input.namespace] = ActiveIndex(manifest, input.documents, normalized)
        persistence.writeBuildState(input.namespace, IndexBuildState.SUCCEEDED)
        return manifest
    }

    override suspend fun search(query: VectorQuery): List<VectorMatch> {
        val active = activeIndex(query.namespace) ?: return emptyList()
        val queryVector = normalizeSingle(query.vector, active.manifest.distanceMetric, active.manifest.dimension)
        val candidates = active.documents.indices
            .filter { idx -> query.filter?.invoke(active.documents[idx].metadata) ?: true }
        val scored = candidates.mapNotNull { idx ->
            val score = dotProduct(queryVector, active.vectors[idx])
            if (query.minScore != null && score < query.minScore) null
            else VectorMatch(
                documentId = active.documents[idx].documentId,
                score = score,
                metadata = active.documents[idx].metadata
            )
        }
        return scored
            .sortedByDescending { it.score }
            .take(query.topK)
    }

    override suspend fun invalidate(reason: IndexInvalidationReason) {
        activeIndexes.keys.toList().forEach { ns ->
            activeIndexes.remove(ns)
            persistence.writeBuildState(ns, IndexBuildState.FAILED)
        }
    }

    /**
     * 回滚到上一有效索引（IVAI-RAG-005 处理：索引完整性校验失败 → 回滚）。
     * 由生命周期在 integrity failed 后调用。
     */
    suspend fun rollback(namespace: String): Boolean {
        val previous = persistence.readPrevious(namespace) ?: return false
        // previous 反转为 active（覆盖当前 active）。
        persistence.writeStaging(namespace, previous.manifest, previous.documents, previous.vectors)
        persistence.publish(namespace)
        activeIndexes[namespace] = ActiveIndex(previous.manifest, previous.documents, previous.vectors)
        return true
    }

    private fun activeIndex(namespace: String): ActiveIndex? =
        activeIndexes[namespace] ?: persistence.readActive(namespace)?.let {
            ActiveIndex(it.manifest, it.documents, it.vectors)
        }?.also { activeIndexes[namespace] = it }

    private fun requireCompatible(active: VectorIndexManifest, expected: VectorIndexManifest) {
        val activeKey = IndexCompatibilityKey.of(active).key()
        val expectedKey = IndexCompatibilityKey.of(expected).key()
        if (activeKey != expectedKey) {
            throw RagException(
                RagErrorCode.MANIFEST_INCOMPATIBLE,
                "Index Manifest 不兼容: active=$activeKey expected=$expectedKey"
            )
        }
        if (active.contentSetHash != expected.contentSetHash) {
            throw RagException(
                RagErrorCode.INTEGRITY_FAILED,
                "索引内容集不匹配: ${active.contentSetHash} != ${expected.contentSetHash}"
            )
        }
    }

    private fun validateVectors(input: VectorIndexBuildInput) {
        input.vectors.forEachIndexed { i, v ->
            if (v.size != input.dimension) {
                throw RagException(
                    RagErrorCode.INVALID_VECTOR,
                    "向量维度非法: doc[${i}] size=${v.size} expected=${input.dimension}"
                )
            }
            if (v.any { it.isNaN() || it.isInfinite() }) {
                throw RagException(
                    RagErrorCode.INVALID_VECTOR,
                    "向量含 NaN/Infinity: doc[$i]"
                )
            }
        }
    }

    private fun normalize(vectors: List<FloatArray>, metric: DistanceMetric): List<FloatArray> =
        vectors.map { normalizeSingle(it, metric, it.size) }

    /** 构建冒烟测试：文档/向量数量、维度与有限值校验（完整性近似校验）。 */
    private fun smokeTest(
        namespace: String,
        manifest: VectorIndexManifest,
        documents: List<IndexedDocument>,
        vectors: List<FloatArray>
    ): Boolean {
        if (documents.size != vectors.size) return false
        if (manifest.documentCount != documents.size) return false
        if (vectors.any { it.size != manifest.dimension }) return false
        if (vectors.any { it.any { f -> f.isNaN() || f.isInfinite() } }) return false
        return true
    }

    private class LocalIndexHandle(
        private val index: ActiveIndex
    ) : VectorIndexHandle {
        override val manifest: VectorIndexManifest get() = index.manifest
        override val size: Int get() = index.documents.size

        override suspend fun search(query: VectorQuery): List<VectorMatch> {
            val q = normalizeSingle(query.vector, index.manifest.distanceMetric, index.manifest.dimension)
            val candidates = index.documents.indices
                .filter { idx -> query.filter?.invoke(index.documents[idx].metadata) ?: true }
            return candidates.mapNotNull { idx ->
                val score = dotProduct(q, index.vectors[idx])
                if (query.minScore != null && score < query.minScore) null
                else VectorMatch(index.documents[idx].documentId, score, index.documents[idx].metadata)
            }.sortedByDescending { it.score }.take(query.topK)
        }
    }

    private data class ActiveIndex(
        val manifest: VectorIndexManifest,
        val documents: List<IndexedDocument>,
        val vectors: List<FloatArray>
    )
}

private fun normalizeSingle(v: FloatArray, metric: DistanceMetric, expectedDim: Int): FloatArray {
    if (v.size != expectedDim) {
        throw RagException(
            RagErrorCode.INVALID_VECTOR,
            "向量维度非法: size=${v.size} expected=$expectedDim"
        )
    }
    return when (metric) {
        DistanceMetric.COSINE -> {
            val norm = sqrt(v.sumOf { (it.toDouble() * it) }).toFloat()
            if (norm == 0f) v.copyOf() else FloatArray(v.size) { v[it] / norm }
        }
        DistanceMetric.DOT -> v.copyOf()
        DistanceMetric.EUCLIDEAN -> v.copyOf()
    }
}

private fun dotProduct(a: FloatArray, b: FloatArray): Double {
    var sum = 0.0
    for (i in a.indices) sum += a[i].toDouble() * b[i]
    return sum
}
