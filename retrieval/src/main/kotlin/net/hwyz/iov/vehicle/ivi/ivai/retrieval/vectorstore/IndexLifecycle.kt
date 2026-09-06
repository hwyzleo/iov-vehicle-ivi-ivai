package net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagException
import java.util.concurrent.ConcurrentHashMap

/**
 * 索引构建请求（CR-011 索引构建生命周期）。
 *
 * [documents] 为当前治理/知识内容生成的完整文档集；[indexVersion] 随文档构建
 * 版本或强制重建递增。模型或文档构建版本变化时必须全量重建；仅治理内容少量
 * 变化且兼容键不变时允许按 [IndexedDocument.contentHash] 增量更新。
 */
data class IndexBuildRequest(
    val namespace: String,
    val documents: List<IndexedDocument>,
    val providerType: String,
    val modelId: String,
    val modelVersion: String?,
    val dimension: Int,
    val distanceMetric: DistanceMetric,
    val documentBuilderVersion: String,
    val governanceVersion: String,
    val indexVersion: String,
    val forceFullRebuild: Boolean = false
)

/**
 * 索引生命周期协调器（CR-011）。
 *
 * 流程：Load Governance Manifest → Assemble eligible runtime assets → Build
 * deterministic RetrievalDocuments → Compare contentHash / compatibility key →
 * Batch Embedding → Validate dimension and finite values → Write staging index →
 * Integrity + retrieval smoke test → Atomic publish → Retain previous valid
 * index for rollback。
 *
 * 并发：同一命名空间同一兼容键只允许一个构建任务（[Mutex] 互斥）；调用方
 * 协程取消会中断 Embedding 阶段，staging 由存储层删除，不影响 active。
 */
class IndexLifecycle(
    private val store: LocalExactVectorStore,
    private val persistence: VectorPersistence,
    private val embeddingProvider: EmbeddingProvider
) {

    private val buildLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * 确保 [request.namespace] 存在与 [request] 兼容的最新索引。
     *
     * @return 当前（已发布）Manifest；若索引已是最新则直接返回，不重复构建。
     */
    suspend fun ensureIndex(request: IndexBuildRequest): VectorIndexManifest {
        val lock = buildLocks.computeIfAbsent(request.namespace) { Mutex() }
        return lock.withLock {
            val active = persistence.readActive(request.namespace)
            if (active != null && !request.forceFullRebuild) {
                val activeKey = IndexCompatibilityKey.of(active.manifest).key()
                val newKey = IndexCompatibilityKey(
                    providerType = request.providerType,
                    modelId = request.modelId,
                    modelVersion = request.modelVersion,
                    dimension = request.dimension,
                    distanceMetric = request.distanceMetric,
                    documentBuilderVersion = request.documentBuilderVersion
                ).key()
                if (activeKey == newKey && active.manifest.contentSetHash == requestContentSetHash(request)) {
                    // 兼容键与内容集均未变化 → 无需重建。
                    return@withLock active.manifest
                }
                if (activeKey == newKey) {
                    // 兼容键不变、内容变化 → 按 contentHash 增量更新。
                    return@withLock buildIncrementally(request, active)
                }
                // 兼容键变化（模型/构建版本/度量/维度切换）→ 全量重建。
                store.invalidate(IndexInvalidationReason.MODEL_CHANGED)
            }
            fullBuild(request)
        }
    }

    private suspend fun buildIncrementally(request: IndexBuildRequest, active: StoredIndex): VectorIndexManifest {
        // active 文档 → (IndexedDocument, vector) 映射，按 contentHash 复用未变化文档的向量。
        val activeVectorsByDoc: Map<IndexedDocument, FloatArray> =
            active.documents.zip(active.vectors).toMap()
        val mergedVectors = MutableList<FloatArray?>(request.documents.size) { null }
        request.documents.forEachIndexed { idx, doc ->
            val prev = activeVectorsByDoc.entries.firstOrNull { it.key.documentId == doc.documentId }
            if (prev != null && prev.key.contentHash == doc.contentHash) {
                mergedVectors[idx] = prev.value
            }
        }
        val toEmbedIdx = mergedVectors.indices.filter { mergedVectors[it] == null }
        if (toEmbedIdx.isNotEmpty()) {
            val newVectors = embed(toEmbedIdx.map { request.documents[it] })
            toEmbedIdx.forEachIndexed { i, idx -> mergedVectors[idx] = newVectors[i] }
        }
        return publish(request, request.documents, mergedVectors.map { requireNotNull(it) })
    }

    private suspend fun fullBuild(request: IndexBuildRequest): VectorIndexManifest {
        return try {
            val vectors = embed(request.documents)
            publish(request, request.documents, vectors)
        } catch (e: RagException) {
            // Embedding 阶段失败只删除 staging，不影响 active index。
            persistence.deleteStaging(request.namespace)
            persistence.writeBuildState(request.namespace, IndexBuildState.FAILED)
            throw e
        }
    }

    private suspend fun embed(documents: List<IndexedDocument>): List<FloatArray> {
        if (!embeddingProvider.available) {
            throw RagException(
                RagErrorCode.EMBEDDING_UNAVAILABLE,
                "Embedding 服务不可用: model=${embeddingProvider.descriptor.modelId}"
            )
        }
        val response = embeddingProvider.embed(EmbeddingRequest(documents.map { it.text }))
        if (response.vectors.size != documents.size) {
            throw RagException(
                RagErrorCode.EMBEDDING_UNAVAILABLE,
                "Embedding 返回数量不一致: ${response.vectors.size} != ${documents.size}"
            )
        }
        return response.vectors
    }

    private suspend fun publish(
        request: IndexBuildRequest,
        documents: List<IndexedDocument>,
        vectors: List<FloatArray>
    ): VectorIndexManifest {
        val input = VectorIndexBuildInput(
            namespace = request.namespace,
            documents = documents,
            vectors = vectors,
            providerType = request.providerType,
            modelId = request.modelId,
            modelVersion = request.modelVersion,
            dimension = request.dimension,
            distanceMetric = request.distanceMetric,
            documentBuilderVersion = request.documentBuilderVersion,
            governanceVersion = request.governanceVersion,
            contentSetHash = requestContentSetHash(request),
            indexVersion = request.indexVersion
        )
        return try {
            store.build(input)
        } catch (e: RagException) {
            // 构建失败只删除 staging；若 active 已失效则回滚上一有效索引。
            persistence.deleteStaging(request.namespace)
            persistence.writeBuildState(request.namespace, IndexBuildState.FAILED)
            val active = persistence.readActive(request.namespace)
            if (active == null) {
                store.rollback(request.namespace)
            }
            throw e
        }
    }

    private fun requestContentSetHash(request: IndexBuildRequest): String {
        val hashes = request.documents.sortedBy { it.documentId }
            .joinToString("\u0000") { "${it.documentId}=${it.contentHash}" }
        return net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.ContentHash.stableHash(hashes)
    }
}
