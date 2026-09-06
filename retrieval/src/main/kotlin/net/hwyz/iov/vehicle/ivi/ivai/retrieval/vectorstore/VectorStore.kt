package net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore

import kotlinx.serialization.Serializable

/**
 * 向量距离度量（CR-011）。文档向量和查询向量都归一化后，点积等价于余弦
 * 相似度；兼容键必须包含度量，避免度量切换复用旧向量。
 */
@Serializable
enum class DistanceMetric {
    COSINE,
    EUCLIDEAN,
    DOT
}

/**
 * 向量存储类型（CR-011）。首期 LOCAL_EXACT：SQLite 持久化 + 内存归一化向量
 * 精确余弦；规模超出阈值后可在不改变上层 Retriever 的前提下替换。
 */
@Serializable
enum class VectorStoreType {
    LOCAL_EXACT,
    LOCAL_ANN,
    REMOTE
}

/**
 * 向量索引 Manifest（CR-011）。
 *
 * 兼容键至少包括：providerType、modelId、modelVersion、dimension、
 * distanceMetric、documentBuilderVersion（见 [IndexCompatibilityKey]）。
 * 任一字段变化均视为旧索引不兼容。
 */
@Serializable
data class VectorIndexManifest(
    val indexId: String,
    val indexVersion: String,
    /** 索引命名空间：tool-intent / knowledge，两条路径必须独立。 */
    val namespace: String,
    val governanceVersion: String,
    val embeddingProviderType: String,
    val modelId: String,
    val modelVersion: String?,
    val dimension: Int,
    val distanceMetric: DistanceMetric,
    val documentBuilderVersion: String,
    val documentCount: Int,
    val contentSetHash: String,
    val createdAtEpochMillis: Long
)

/**
 * 索引失效原因（CR-011）。模型切换、治理变更、完整性失败、显式重建请求或
 * Manifest 不兼容均使旧索引失效；失效后按生命周期重新构建（staging →
 * 原子发布），不删除上一有效索引直到新索引冒烟通过。
 */
enum class IndexInvalidationReason {
    MODEL_CHANGED,
    GOVERNANCE_CHANGED,
    INTEGRITY_FAILED,
    REBUILD_REQUESTED,
    MANIFEST_INCOMPATIBLE
}

/**
 * 构建输入（CR-011）。[documents] 提供文档元数据（documentId + 文本），
 * [vectors] 为按序对应的归一化向量；[descriptor] 记录使用的 Embedding 模型，
 * 写入 Manifest 供兼容校验。
 */
data class VectorIndexBuildInput(
    val namespace: String,
    val documents: List<IndexedDocument>,
    val vectors: List<FloatArray>,
    val providerType: String,
    val modelId: String,
    val modelVersion: String?,
    val dimension: Int,
    val distanceMetric: DistanceMetric,
    val documentBuilderVersion: String,
    val governanceVersion: String,
    val contentSetHash: String,
    val indexVersion: String
) {
    init {
        require(documents.size == vectors.size) {
            "文档与向量数量不一致: ${documents.size} != ${vectors.size}"
        }
    }
}

/**
 * 一条待索引文档（CR-011）。[metadata] 为治理元数据（Domain/OperationType/
 * Pack/Governance 状态等），查询时先按元数据过滤再检索。[contentHash] 为该
 * 文档内容的确定性哈希：兼容键不变时内容哈希一致的文档在增量更新中复用旧向量。
 */
@Serializable
data class IndexedDocument(
    val documentId: String,
    val text: String,
    val metadata: Map<String, String> = emptyMap(),
    val contentHash: String = ""
)

/**
 * 向量命中（CR-011）。[documentId] 为索引文档 ID；[metadata] 携带治理元数据
 * 供上层做 canonical 去重与加权。
 */
data class VectorMatch(
    val documentId: String,
    val score: Double,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 向量查询（CR-011）。[filter] 为"先过滤再检索"的元数据谓词，返回 false 的
 * 文档不参与点积；[minScore] 阈值过滤在 Top-K 截断之前应用。
 */
data class VectorQuery(
    val namespace: String,
    val vector: FloatArray,
    val topK: Int,
    val minScore: Double? = null,
    val filter: ((Map<String, String>) -> Boolean)? = null
)

/**
 * 只读索引句柄（CR-011 并发语义）。active index 使用只读快照；构建在 staging
 * 完成后原子切换，查询不读取半成品。
 */
interface VectorIndexHandle {
    val manifest: VectorIndexManifest
    val size: Int

    /** 对 [query.vector] 执行精确/近似检索，返回按分数降序的 Top-K。 */
    suspend fun search(query: VectorQuery): List<VectorMatch>
}

/**
 * 向量存储接口（CR-011）。
 *
 * 上层 RagRetriever 只依赖本接口：LOCAL_EXACT / LOCAL_ANN / REMOTE 实现可
 * 互换。同一兼容键只允许一个构建任务（并发互斥由生命周期协调器保证）。
 */
interface VectorStore {
    /** 打开与 [manifest] 兼容的 active index 只读句柄；不兼容抛 RagException。 */
    suspend fun open(manifest: VectorIndexManifest): VectorIndexHandle

    /** 构建（staging）并原子发布，返回新 Manifest；旧索引保留用于回滚。 */
    suspend fun build(input: VectorIndexBuildInput): VectorIndexManifest

    /** 查询当前 active index（[VectorQuery.namespace]）。 */
    suspend fun search(query: VectorQuery): List<VectorMatch>

    /** 使 [reason] 对应索引失效（触发重建或回滚策略）。 */
    suspend fun invalidate(reason: IndexInvalidationReason)
}
