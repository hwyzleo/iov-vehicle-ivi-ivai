package net.hwyz.iov.vehicle.ivi.ivai.retrieval.eval

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType

/**
 * 一条离线评测查询（CR-011）。
 *
 * [expectedIds] 为期望命中的 canonical ID（L1：Tool/Workflow canonical ID；
 * L2：Knowledge sourceId）。[expectEmpty] 表示该查询本应无证据/无匹配（用于
 * 无证据拒答率与无效候选率）。[domainIds]/[operationTypes] 等作为检索过滤维度
 * 在评测中注入查询，验证“先过滤再检索”不误伤。
 */
data class RetrievalEvalQuery(
    val text: String,
    val expectedIds: Set<String> = emptySet(),
    val domainIds: List<BusinessDomainId> = emptyList(),
    val operationTypes: List<OperationType> = emptyList(),
    val vehicleModel: String? = null,
    val softwareVersion: String? = null,
    val language: String = "zh-CN",
    val expectEmpty: Boolean = false
)

/**
 * 评测结果（CR-011 离线评测指标）。
 *
 * L1：Recall@K、MRR、Top-1/Top-3、无效候选率；L2：evidence Recall@K、来源正确率、
 * 版本适用率、无证据拒答率、答案忠实度（[answerFaithfulness] 需结合答案生成，
 * 本模块提供 [FaithfulnessAssessor] 契约）。
 */
data class RetrievalEvalResult(
    val queryCount: Int,
    val recallAt1: Double,
    val recallAt3: Double,
    val recallAtK: Double,
    val mrr: Double,
    val top1Accuracy: Double,
    val top3Accuracy: Double,
    val invalidCandidateRate: Double,
    val noEvidenceRefusalRate: Double,
    val versionApplicabilityRate: Double
) {
    fun summary(): String = buildString {
        appendLine("评测查询数: $queryCount")
        appendLine("Recall@1: ${format(recallAt1)}  Recall@3: ${format(recallAt3)}  Recall@K: ${format(recallAtK)}")
        appendLine("MRR: ${format(mrr)}  Top-1: ${format(top1Accuracy)}  Top-3: ${format(top3Accuracy)}")
        appendLine("无效候选率: ${format(invalidCandidateRate)}  无证据拒答率: ${format(noEvidenceRefusalRate)}")
        appendLine("版本适用率: ${format(versionApplicabilityRate)}")
    }

    private fun format(v: Double): String = String.format("%.4f", v)
}

/**
 * 答案忠实度评估（CR-011 L2）。真实实现接入本地 LLM，判断答案是否只依据
 * 召回片段（grounded）；本评测提供默认的“答案文本是否完全来自片段拼接”的
 * 保守代理。
 */
fun interface FaithfulnessAssessor {
    /** 返回答案相对证据片段的忠实度（0..1）。 */
    fun assess(answer: String, evidenceTexts: List<String>): Double
}

/** 保守代理：答案中的非证据来源字句越少越忠实。 */
val ConservativeFaithfulness: FaithfulnessAssessor = FaithfulnessAssessor { answer, evidenceTexts ->
    if (evidenceTexts.isEmpty()) return@FaithfulnessAssessor 0.0
    val union = evidenceTexts.joinToString("")
    if (answer.isBlank()) 0.0
    else {
        // 按字符窗口近似：答案中来自证据片段的字符占比。
        val matched = answer.toCharArray().count { ch -> union.contains(ch) }.toDouble()
        matched / answer.length
    }
}

/**
 * 评测执行器（CR-011）。[retrieve] 返回按序候选 ID 列表（L1 canonical / L2
 * sourceId），[eligible] 返回合法候选全集（L1 运行时候选集 / L2 批准来源），
 * 用于计算无效候选率。
 */
object RetrievalEvalRunner {

    suspend fun evaluate(
        queries: List<RetrievalEvalQuery>,
        eligible: Set<String>,
        topK: Int,
        retrieve: suspend (RetrievalEvalQuery, topK: Int) -> List<String>,
        faithfulness: FaithfulnessAssessor? = null
    ): RetrievalEvalResult {
        val recallAt1 = DoubleArray(queries.size)
        val recallAt3 = DoubleArray(queries.size)
        val recallAtK = DoubleArray(queries.size)
        val mrr = DoubleArray(queries.size)
        val top1 = DoubleArray(queries.size)
        val top3 = DoubleArray(queries.size)
        val invalidCandidates = mutableListOf<Int>()
        val refusalOk = mutableListOf<Boolean>()
        val versionApplicable = mutableListOf<Boolean>()

        queries.forEachIndexed { i, q ->
            val results = retrieve(q, topK)
            val ids = results
            val expected = q.expectedIds
            val expectedCount = expected.size.coerceAtLeast(1)

            if (q.expectEmpty) {
                refusalOk += ids.isEmpty()
            }
            // 版本适用率：查询指定版本且期望命中时，结果里应包含适用片段。
            if (q.softwareVersion != null && expected.isNotEmpty()) {
                versionApplicable += ids.any { it in expected }
            }
            val hits1 = ids.take(1).count { it in expected }
            val hits3 = ids.take(3).count { it in expected }
            val hitsK = ids.take(topK).count { it in expected }
            recallAt1[i] = hits1.toDouble() / expectedCount
            recallAt3[i] = hits3.toDouble() / expectedCount
            recallAtK[i] = hitsK.toDouble() / expectedCount
            val firstHitRank = ids.indexOfFirst { it in expected }
            mrr[i] = if (firstHitRank >= 0) 1.0 / (firstHitRank + 1) else 0.0
            top1[i] = if (ids.firstOrNull() in expected) 1.0 else 0.0
            top3[i] = if (ids.take(3).any { it in expected }) 1.0 else 0.0
            invalidCandidates += ids.count { it !in eligible }
        }

        val totalReturned = queries.size * topK
        return RetrievalEvalResult(
            queryCount = queries.size,
            recallAt1 = recallAt1.average(),
            recallAt3 = recallAt3.average(),
            recallAtK = recallAtK.average(),
            mrr = mrr.average(),
            top1Accuracy = top1.average(),
            top3Accuracy = top3.average(),
            invalidCandidateRate = if (totalReturned > 0) invalidCandidates.sum().toDouble() / totalReturned else 0.0,
            noEvidenceRefusalRate = refusalOk.takeIf { it.isNotEmpty() }?.count { it }?.toDouble()?.div(refusalOk.size) ?: 0.0,
            versionApplicabilityRate = versionApplicable.takeIf { it.isNotEmpty() }?.count { it }?.toDouble()?.div(versionApplicable.size) ?: 0.0
        )
    }
}
