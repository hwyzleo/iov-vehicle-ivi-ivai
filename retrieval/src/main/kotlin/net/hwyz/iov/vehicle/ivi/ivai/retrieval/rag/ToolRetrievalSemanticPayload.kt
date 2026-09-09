package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.PositionAliasLexicon
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ClimateToolBoundaryCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.TemperatureToolBoundaryCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema.CanonicalSchemaParser

/**
 * Tool Retrieval 语义载荷（IVI-IVAI-DSN-CR-017）。
 *
 * 一个 canonical Tool 生成一个载荷，[SemanticTextTemplate.tool] 按稳定模板拼接
 * 为 semanticText。必须包含 Tool 名称与职责、操作/对象语义、参数 Schema（含
 * canonical enum 与缺省）、批准 Alias、分区正例、负例边界、冲突 Tool 与来源版本；
 * 禁止只索引 Tool ID 与一句描述（IVAI-RAG-DOC-001 校验项）。
 */
data class RetrievalParameterSchema(
    val name: String,
    val type: String,
    val enum: List<String> = emptyList(),
    val required: Boolean = false,
    val defaultValue: String? = null
)

data class ToolRetrievalSemanticPayload(
    val canonicalId: String,
    val title: String,
    val responsibility: String,
    val operationSemantics: Set<String>,
    val objectSemantics: Set<String>,
    val parameterSchemas: List<RetrievalParameterSchema>,
    /** 字段级批准 Alias（position：zone 字段的 vehicle_position_v2 Alias）。 */
    val approvedAliases: Map<String, Set<String>>,
    val positiveExamples: List<String>,
    val negativeBoundaries: List<String>,
    val conflictingToolIds: Set<String>,
    val sourceVersions: Set<String>,
    /** CR-018: 正向对象证据（HVAC_SYSTEM / VENT / FAN_SPEED / AIRFLOW_DIRECTION / AUTO_HVAC）。 */
    val positiveObjects: Set<String> = emptySet(),
    /** CR-018: 正向动作证据（open/close/start/absolute/relative/face/feet…）。 */
    val positiveActions: Set<String> = emptySet(),
    /** CR-018: 必填槽位（zone/level/mode/enabled/direction/step）。 */
    val requiredSlots: Set<String> = emptySet(),
    /** CR-019: 温度操作语义（RELATIVE_DELTA/ABSOLUTE_TARGET/BOUND_TARGET）。 */
    val temperatureOperationSemantics: Set<String> = emptySet(),
    /** CR-019: 温度数值角色（delta/target/bound）。 */
    val temperatureValueRoles: Set<String> = emptySet()
)

/** 从治理 ToolDefinition 构建语义载荷（单一来源，供 RAG 文档与评测复用）。 */
object ToolRetrievalPayloadFactory {

    /**
     * @param conflictToolIds 冲突 Tool 集（来自 L0 DeterministicIntentProfile；可空）。
     * @param sourceVersions  来源版本（治理版本 / 目录版本 / Alias 词表版本等）。
     */
    fun fromTool(
        tool: ToolDefinition,
        conflictToolIds: Set<String> = emptySet(),
        sourceVersions: Set<String> = emptySet()
    ): ToolRetrievalSemanticPayload {
        val schema = CanonicalSchemaParser.parse(tool.parameterSchema)
        val parameterSchemas = schema.properties.map { prop ->
            RetrievalParameterSchema(
                name = prop.name,
                type = prop.type,
                enum = prop.enum ?: emptyList(),
                required = prop.name in schema.required,
                defaultValue = prop.defaultValue?.toString()
            )
        }
        // 字段级批准 Alias：zone 参数挂载 vehicle_position_v2（同源治理）；其余参数无字段词表。
        val zoneAliases = if (parameterSchemas.any { it.name == "zone" || it.name == "position" }) {
            mapOf("zone" to PositionAliasLexicon.APPROVED_ALIASES.values.flatten().toSet())
        } else {
            emptyMap()
        }
        // CR-018：空调相似 Tool 边界证据（对象/动作/必填槽位/负例），与 Tool 自身正负例
        // **合并**（不得覆盖）——Catalog 正例变化仍必须引起 contentHash 变化（REQ-177）。
        val boundary = ClimateToolBoundaryCatalog.forTool(tool.toolId)
        // CR-019：温度 Tool 边界证据（操作语义/数值角色/正反例/冲突集）。
        val temperatureBoundary = TemperatureToolBoundaryCatalog.forTool(tool.toolId)
        val effectiveConflicts = if (conflictToolIds.isNotEmpty()) conflictToolIds else {
            boundary?.conflictToolIds ?: temperatureBoundary?.conflictToolIds ?: emptySet()
        }
        return ToolRetrievalSemanticPayload(
            canonicalId = tool.toolId,
            title = tool.name,
            responsibility = tool.description,
            operationSemantics = tool.supportedOperations.map { it.name }.toSet(),
            objectSemantics = SemanticTextTemplate.objectsFromSchema(tool.parameterSchema)
                .split("、").filter { it.isNotBlank() }.toSet(),
            parameterSchemas = parameterSchemas,
            approvedAliases = zoneAliases,
            positiveExamples = (boundary?.positiveExamples.orEmpty() +
                temperatureBoundary?.positiveExamples.orEmpty() + tool.positiveExamples).distinct(),
            negativeBoundaries = (boundary?.negativeExamples.orEmpty() +
                temperatureBoundary?.negativeBoundaries.orEmpty() + tool.negativeExamples).distinct(),
            conflictingToolIds = effectiveConflicts,
            sourceVersions = sourceVersions +
                setOfNotNull(boundary?.governanceVersion, temperatureBoundary?.let {
                    TemperatureToolBoundaryCatalog.GOVERNANCE_VERSION
                }),
            positiveObjects = boundary?.positiveObjects ?: emptySet(),
            positiveActions = boundary?.positiveActions ?: emptySet(),
            requiredSlots = boundary?.requiredSlots ?: emptySet(),
            temperatureOperationSemantics = temperatureBoundary?.operationSemantics ?: emptySet(),
            temperatureValueRoles = temperatureBoundary?.valueRoles ?: emptySet()
        )
    }
}
