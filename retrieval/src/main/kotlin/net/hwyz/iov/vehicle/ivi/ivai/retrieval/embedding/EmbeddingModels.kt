package net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding

/**
 * Embedding 模型描述（CR-011）。[providerType]/[modelId]/[modelVersion]/
 * [dimension] 组成兼容键的一部分，Manifest 校验时对比。
 */
data class EmbeddingModelDescriptor(
    val providerType: String,
    val modelId: String,
    val modelVersion: String? = null,
    val dimension: Int
)

/**
 * 嵌入请求（CR-011）。[batchSize] 允许调用方控制单批文本数；文档批量 Embedding
 * 与用户查询 Embedding 使用独立限流预算，避免索引重建阻塞交互请求。
 */
data class EmbeddingRequest(
    val texts: List<String>,
    val batchSize: Int = DEFAULT_BATCH_SIZE
) {
    companion object {
        const val DEFAULT_BATCH_SIZE = 16
    }
}

/**
 * 嵌入响应（CR-011）。[vectors] 与请求文本按序对应，长度必须等于 [dimension]；
 * 非法维度 / NaN / Infinity 由 Provider 或上层校验拒绝（IVAI-RAG-003）。
 */
data class EmbeddingResponse(
    val vectors: List<FloatArray>,
    val modelId: String,
    val dimension: Int
)
