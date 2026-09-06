package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rerank

import kotlinx.serialization.Serializable

/**
 * Reranker 类型（CR-011）。首期 NONE：L1/L2 均保持各自基础顺序；由评测证据
 * 决定后续是否引入 HTTP_COMPATIBLE / LOCAL。
 */
@Serializable
enum class RerankerType {
    NONE,
    HTTP_COMPATIBLE,
    LOCAL
}

/**
 * 待重排候选（CR-011）。Reranker 只能重排对应路径已经过滤的结果：L1 不得补入
 * 统一运行时候选集之外的资产，L2 不得补入批准 Knowledge Corpus 之外或车型/
 * 版本不适用的片段。
 */
data class RetrievalCandidate(
    val documentId: String,
    val canonicalId: String? = null,
    val text: String,
    val score: Double,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Reranker Provider 接口（CR-011）。返回顺序即最终注入顺序。
 */
interface RerankerProvider {
    val type: RerankerType

    suspend fun rerank(query: String, candidates: List<RetrievalCandidate>): List<RetrievalCandidate>
}

/**
 * 首期实现（CR-011）：不调用强制重排，保持候选基础顺序。
 */
class NoopRerankerProvider : RerankerProvider {
    override val type: RerankerType = RerankerType.NONE

    override suspend fun rerank(query: String, candidates: List<RetrievalCandidate>): List<RetrievalCandidate> =
        candidates
}
