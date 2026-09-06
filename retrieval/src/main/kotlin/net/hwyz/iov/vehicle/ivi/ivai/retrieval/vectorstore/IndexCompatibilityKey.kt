package net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore

/**
 * 索引兼容键（CR-011）。
 *
 * 至少包括 providerType、modelId、modelVersion、dimension、distanceMetric、
 * documentBuilderVersion。任一字段变化均视为旧索引不兼容 → IVAI-RAG-004，
 * 必须使旧索引失效并全量重建，防止向量空间不一致。
 */
data class IndexCompatibilityKey(
    val providerType: String,
    val modelId: String,
    val modelVersion: String?,
    val dimension: Int,
    val distanceMetric: DistanceMetric,
    val documentBuilderVersion: String
) {
    /** 稳定字符串形式，用于存储层 key。 */
    fun key(): String = listOf(
        providerType,
        modelId,
        modelVersion ?: "",
        dimension.toString(),
        distanceMetric.name,
        documentBuilderVersion
    ).joinToString("|")

    override fun toString(): String = key()

    companion object {
        fun of(manifest: VectorIndexManifest) = IndexCompatibilityKey(
            providerType = manifest.embeddingProviderType,
            modelId = manifest.modelId,
            modelVersion = manifest.modelVersion,
            dimension = manifest.dimension,
            distanceMetric = manifest.distanceMetric,
            documentBuilderVersion = manifest.documentBuilderVersion
        )
    }
}
