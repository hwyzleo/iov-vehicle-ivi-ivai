package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import kotlinx.serialization.Serializable
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rerank.RerankerType
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.DistanceMetric
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorStoreType

/**
 * CR-011 端侧双路径 RAG 配置模型。
 *
 * L1 Tool/Intent 与 L2 Knowledge 两条路径共享 [embedding] 与 [vectorStore]，
 * 但使用独立 [toolRetrieval] / [knowledgeRetrieval] 命名空间、开关与 Top-K。
 * [reranker] 首期默认 NONE。
 */
@Serializable
data class RagConfig(
    val enabled: Boolean = false,
    val embedding: EmbeddingConfig = EmbeddingConfig(),
    val vectorStore: VectorStoreConfig = VectorStoreConfig(),
    val toolRetrieval: RetrievalPathConfig = RetrievalPathConfig(indexNamespace = "tool-intent"),
    val knowledgeRetrieval: RetrievalPathConfig = RetrievalPathConfig(indexNamespace = "knowledge"),
    val reranker: RerankerConfig = RerankerConfig(type = RerankerType.NONE)
)

/**
 * Embedding Provider 配置（CR-011）。[credentialRef] 指向 Keystore/安全配置，
 * 不保存密钥明文。配置加载后校验 HTTPS、允许的 Host、维度、超时、批量大小和
 * 模型标识。Release 环境不得允许任意 URL 动态注入，[allowedHosts] 为空表示
 * 不限制（仅测试/本地使用）。
 */
@Serializable
data class EmbeddingConfig(
    val providerType: String = "HTTP_COMPATIBLE",
    val baseUrl: String = "",
    val modelId: String = "",
    val modelVersion: String? = null,
    val credentialRef: String = "",
    val dimension: Int = 0,
    val distanceMetric: DistanceMetric = DistanceMetric.COSINE,
    val timeoutMs: Long = 10_000L,
    val maxRetries: Int = 2,
    val batchSize: Int = 16,
    val allowedHosts: List<String> = emptyList()
)

/**
 * 本地向量存储配置（CR-011）。首期 LOCAL_EXACT；当知识片段数量、内存或延迟
 * 超过阈值时可在不改变上层 Retriever 的前提下替换为 LOCAL_ANN / REMOTE。
 */
@Serializable
data class VectorStoreConfig(
    val type: VectorStoreType = VectorStoreType.LOCAL_EXACT,
    val exactSearchThreshold: Int = 10_000
)

/**
 * 单路径检索配置（CR-011）。
 *
 * [indexNamespace] 两条路径必须独立（tool-intent / knowledge）；[topK] 为检索
 * 返回上限，[maxContextItems] 为注入 LLM 上下文的上限（L1 首期默认 10 / 5，
 * L2 独立配置）。具体值由各自评测集调整，不写死在业务逻辑中。
 */
@Serializable
data class RetrievalPathConfig(
    val enabled: Boolean = true,
    val indexNamespace: String = "tool-intent",
    val topK: Int = 10,
    val minScore: Double? = null,
    val maxContextItems: Int = 5,
    val exactSearchThreshold: Int = 10_000
)

/**
 * Reranker 配置（CR-011）。首期 NONE：L1/L2 均保持各自基础顺序，由评测证据
 * 决定后续是否引入 HTTP_COMPATIBLE / LOCAL。
 */
@Serializable
data class RerankerConfig(
    val type: RerankerType = RerankerType.NONE
)
