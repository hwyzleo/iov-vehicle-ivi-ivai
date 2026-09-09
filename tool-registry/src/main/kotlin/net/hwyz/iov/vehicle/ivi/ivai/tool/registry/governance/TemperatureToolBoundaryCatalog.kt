package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.TemperatureBoundLexicon

/**
 * 温度相似 Tool 边界文档（IVI-IVAI-DSN-CR-019，对应设计
 * TemperatureToolBoundaryDocument）。
 *
 * 每个温度 Tool 固化：职责、操作语义（RELATIVE_DELTA / ABSOLUTE_TARGET /
 * BOUND_TARGET）、数值角色（delta/target/bound）、必填槽位、正例、负边界与
 * 冲突 Tool 集。只作为 RAG 文档 / Candidate Context / 混合排序的证据来源，
 * 不产生 L0 规则。
 */
data class TemperatureToolBoundaryDocument(
    val canonicalId: String,
    val responsibility: String,
    /** 操作语义（TemperatureOperationSemantic.name 集合）。 */
    val operationSemantics: Set<String>,
    /** 数值角色（delta / target / bound）。 */
    val valueRoles: Set<String>,
    val requiredSlots: Set<String>,
    val parameterSchema: String,
    val positiveExamples: List<String>,
    val negativeBoundaries: List<String>,
    val conflictToolIds: Set<String>,
    val sourceVersions: Set<String>,
    val contentHash: String
)

/**
 * 温度相似 Tool 边界目录（IVI-IVAI-DSN-CR-019，治理单一事实源）。
 *
 * 职责划分（CR-019 设计「Tool Catalog 与 RAG 边界」）：
 *  - climate.temperature.adjust：当前温度基础上相对升高/降低；
 *  - climate.temperature.set：设置绝对目标温度（合法绝对温度或批准 bound Alias）。
 *
 * 关键约束：
 *  - “空调”只形成 HVAC 对象证据，不得直接提升 power.set；
 *  - “温度+数值”不得仅因数值提升 fan.speed.set；
 *  - 绝对越界温度阻止可执行候选（IVAI-TEMP-RANGE-001）。
 */
object TemperatureToolBoundaryCatalog {

    const val GOVERNANCE_VERSION = "ivai-temperature-boundary-v1"

    val ALL: Map<String, TemperatureToolBoundaryDocument> = listOf(
        adjust(),
        set()
    ).associateBy { it.canonicalId }

    fun forTool(toolId: String): TemperatureToolBoundaryDocument? = ALL[toolId]

    /** 稳定内容 Hash（目录 / 词典 / Schema 变化必须触发 contentHash、Embedding 刷新与索引切换）。 */
    fun contentHashOf(doc: TemperatureToolBoundaryDocument): String = GovernanceManifestBuilder.sha256(
        buildString {
            append(doc.canonicalId).append('|')
            append(doc.responsibility).append('|')
            append(doc.operationSemantics.sorted().joinToString(",")).append('|')
            append(doc.valueRoles.sorted().joinToString(",")).append('|')
            append(doc.requiredSlots.sorted().joinToString(",")).append('|')
            append(doc.parameterSchema.replace(Regex("\\s+"), "")).append('|')
            append(doc.positiveExamples.joinToString(";")).append('|')
            append(doc.negativeBoundaries.joinToString(";")).append('|')
            append(doc.conflictToolIds.sorted().joinToString(",")).append('|')
            append(doc.sourceVersions.sorted().joinToString(","))
        }
    )

    private fun adjust(): TemperatureToolBoundaryDocument {
        val doc = TemperatureToolBoundaryDocument(
            canonicalId = "climate.temperature.adjust",
            responsibility = "在当前温度基础上相对升高或降低（direction 必填，step 可由数值或定性幅度词典获得）",
            operationSemantics = setOf(
                TemperatureOperationSemantic.RELATIVE_DELTA.name
            ),
            valueRoles = setOf("delta", "step"),
            requiredSlots = setOf("direction"),
            parameterSchema = "{zone:enum, direction:increase|decrease, step:number[0.5..5]}",
            positiveExamples = listOf(
                "温度调高一点", "温度调高", "调高温度", "温度调低一点", "温度调低", "调低温度",
                "升高温度", "降低温度", "温度升高2度", "温度降低一点",
                "主驾升温", "驾驶位升温", "副驾降温", "温度加1度", "温度减0.5度"
            ),
            negativeBoundaries = listOf(
                "温度调到25度", "温度设为24度", "目标温度24度", "温度保持在26度",
                "设为最低", "设为最高", "查询温度", "现在多少度"
            ),
            conflictToolIds = setOf("climate.temperature.set", "climate.status.query"),
            sourceVersions = setOf(
                GOVERNANCE_VERSION,
                TemperatureDeltaAliasCatalog.GOVERNANCE_VERSION,
                "vehicle_position_v2",
                TemperatureBoundLexicon.GOVERNANCE_VERSION
            ),
            contentHash = ""
        )
        return doc.copy(contentHash = contentHashOf(doc))
    }

    private fun set(): TemperatureToolBoundaryDocument {
        val doc = TemperatureToolBoundaryDocument(
            canonicalId = "climate.temperature.set",
            responsibility = "设置绝对目标温度（合法绝对温度或批准的 bound Alias 必填；越界拒绝）",
            operationSemantics = setOf(
                TemperatureOperationSemantic.ABSOLUTE_TARGET.name,
                TemperatureOperationSemantic.BOUND_TARGET.name
            ),
            valueRoles = setOf("target", "bound"),
            requiredSlots = setOf("temperature"),
            parameterSchema = "{zone:enum, temperature:number[16..30], unit:C}",
            positiveExamples = listOf(
                "温度调到24度", "温度设为26度", "空调设成25度", "目标温度24度",
                "温度保持在26度", "设置温度到24度", "最高温度", "最低温度",
                "温度设为最高", "温度调到最低"
            ),
            negativeBoundaries = listOf(
                "温度调高一点", "温度调高", "调低温度", "升高温度", "温度升高2度",
                "温度降低一点", "主驾升温", "降温", "查询温度"
            ),
            conflictToolIds = setOf("climate.temperature.adjust", "climate.status.query"),
            sourceVersions = setOf(
                GOVERNANCE_VERSION,
                TemperatureBoundLexicon.GOVERNANCE_VERSION,
                "vehicle_position_v2"
            ),
            contentHash = ""
        )
        return doc.copy(contentHash = contentHashOf(doc))
    }
}
