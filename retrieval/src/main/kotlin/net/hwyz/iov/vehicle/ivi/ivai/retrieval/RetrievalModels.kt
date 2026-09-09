package net.hwyz.iov.vehicle.ivi.ivai.retrieval

import kotlinx.serialization.Serializable
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionConstraint
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType

/**
 * Query payloads, candidates and shared models for Tool/Intent RAG and
 * Knowledge RAG (IVI-IVAI-DSN-CR-005 + CR-008 + CR-011).
 */

/** Query passed to a [ToolRetriever]. */
data class ToolRetrievalQuery(
    val text: String,
    val vehicleModel: String? = null,
    val softwareVersion: String? = null,
    val language: String = "zh-CN",
    val excludeToolIds: Set<String> = emptySet(),
    /** CR-008: 非空时只允许这些业务领域的 Tool 进入召回（先过滤再检索）。 */
    val domainIds: List<BusinessDomainId> = emptyList(),
    /** CR-008: 非空时只允许这些 Capability Pack 内的 Tool 进入召回。 */
    val capabilityPackIds: List<String> = emptyList(),
    /** CR-011: 非空时只允许这些 OperationType 的 Tool 进入召回。 */
    val operationTypes: List<OperationType> = emptyList(),
    /** CR-011: 统一运行时能力集（RuntimeCapabilityAssembler 输出）过滤。 */
    val runtimeCapabilityToolIds: Set<String> = emptySet()
)

/** Query passed to a [KnowledgeRetriever]. */
data class KnowledgeRetrievalQuery(
    val text: String,
    val vehicleModel: String? = null,
    val softwareVersion: String? = null,
    val language: String = "zh-CN",
    /** CR-011: 非空时只允许这些已批准来源进入召回。 */
    val sourceIds: Set<String> = emptySet()
)

/** Either a tool or a knowledge retrieval request, carried by a tier decision. */
sealed interface RetrievalQuery {
    data class Tools(val query: ToolRetrievalQuery) : RetrievalQuery
    data class Knowledge(val query: KnowledgeRetrievalQuery) : RetrievalQuery
}

/**
 * Compact, serialization-friendly summary of a tool used for L1 prompt assembly.
 */
@Serializable
data class ToolDefinitionSummary(
    val toolId: String,
    val functionId: String? = null,
    val name: String,
    val description: String,
    val positiveExamples: List<String> = emptyList(),
    val negativeExamples: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val parameterSchema: String = ""
) {
    companion object {
        /** Builds the retrieval/prompt summary from a tool definition (CR-005). */
        fun from(tool: ToolDefinition): ToolDefinitionSummary = ToolDefinitionSummary(
            toolId = tool.toolId,
            functionId = tool.functionId,
            name = tool.name,
            description = tool.description,
            positiveExamples = tool.positiveExamples,
            negativeExamples = tool.negativeExamples,
            synonyms = tool.deterministicRules.flatMap { rule ->
                rule.exactPhrases + rule.synonymPatterns
            }.distinct(),
            parameterSchema = tool.parameterSchema
        )
    }
}

/** A candidate tool recalled by a [ToolRetriever]. */
data class ToolCandidate(
    val toolId: String,
    val score: Double,
    val matchedFields: List<String>,
    val definition: ToolDefinitionSummary,
    /** CR-017: 混合召回分数分解（vectorScore / aliasBoost / slotBoost / operationBoost）。 */
    val boostBreakdown: BoostBreakdown? = null
)

/**
 * 混合召回分数分解（CR-017 + CR-018）。
 *
 * finalScore = vectorScore + aliasBoost + slotBoost + operationBoost
 *            + objectEvidenceBoost + actionEvidenceBoost
 *            − negativeBoundaryPenalty。
 *
 * CR-018：对象/动作/槽位证据与负边界惩罚只作用于 RuntimeCapabilitySet 中的
 * 合法候选；Trace 记录每个分量及最终排序。默认 0 保持 CR-017 既有语义兼容。
 */
data class BoostBreakdown(
    val vectorScore: Double,
    val aliasBoost: Double,
    val slotCoverageBoost: Double,
    val operationBoundaryBoost: Double,
    /** CR-018: 对象证据加权（HVAC_SYSTEM / VENT / FAN_SPEED / AIRFLOW_DIRECTION / AUTO_HVAC）。 */
    val objectEvidenceBoost: Double = 0.0,
    /** CR-018: 动作证据加权（open/close/start/absolute/relative/face/feet…）。 */
    val actionEvidenceBoost: Double = 0.0,
    /** CR-018: 相似 Tool 负边界惩罚（命中负例边界时扣分）。 */
    val negativeBoundaryPenalty: Double = 0.0
) {
    val finalScore: Double
        get() = vectorScore + aliasBoost + slotCoverageBoost + operationBoundaryBoost +
            objectEvidenceBoost + actionEvidenceBoost - negativeBoundaryPenalty
}

/**
 * L2 知识片段模型（IVI-IVAI-DSN-CR-011）。
 *
 * 只允许来自批准的车辆说明书、功能解释、故障帮助及其他端侧可用资料；切分必须
 * 保留标题层级、章节位置、车型/软件版本和来源版本。Tool 文档与 KnowledgeChunk
 * 不得写入同一逻辑索引或互相作为另一条路径的检索结果。
 */
@Serializable
data class KnowledgeChunk(
    val chunkId: String,
    val sourceId: String,
    val sourceType: KnowledgeSourceType,
    val title: String,
    /** 章节路径（"故障/胎压报警"），切分时保留标题层级。 */
    val sectionPath: String,
    val content: String,
    val vehicleModels: Set<String> = setOf("*"),
    val softwareVersions: VersionConstraint = VersionConstraint(),
    val language: String = "zh-CN",
    val sourceVersion: String = "1.0",
    val contentHash: String = ""
)

/**
 * L2 知识来源类型（CR-011）。KnowledgeChunk 只允许来自批准的车辆说明书、功能
 * 解释、故障帮助及其他端侧可用资料。
 */
enum class KnowledgeSourceType {
    MANUAL,
    FEATURE_EXPLANATION,
    FAULT_HELP,
    OTHER_APPROVED
}

/**
 * L2 检索结果（CR-011）。[chunk] 为命中片段，[score] 为相似度/相关性分数，
 * [matchedFields] 记录命中来源（vector/keyword/...）供可观测性与评测。
 */
data class KnowledgeEvidence(
    val chunk: KnowledgeChunk,
    val score: Double,
    val matchedFields: List<String> = emptyList()
)
