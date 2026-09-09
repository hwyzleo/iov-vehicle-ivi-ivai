package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition

/**
 * L1 语义文本模板（CR-011 + CR-017）。
 *
 * semanticText 按固定模板拼接名称、职责、操作边界、对象语义、参数 Schema（含
 * canonical enum 与缺省）、字段级批准 Alias、分区正例、负例边界、冲突 Tool 与
 * 来源版本（REQ-176）；不得只索引 Tool ID 与一句描述。
 * 不得将 Policy 密钥、真实 Binding 地址、用户敏感数据或任意未审核语料写入。
 */
object SemanticTextTemplate {

    /** CR-017 载荷渲染（推荐入口）。 */
    fun tool(payload: ToolRetrievalSemanticPayload): String = buildString {
        appendLine("工具：${payload.title}")
        appendLine("职责：${payload.responsibility}")
        appendLine("操作：${payload.operationSemantics.sorted().joinToString("、")}")
        if (payload.objectSemantics.isNotEmpty()) {
            appendLine("对象：${payload.objectSemantics.sorted().joinToString("、")}")
        }
        // CR-018：正向对象/动作证据与必填槽位（相似 Tool 边界）。
        if (payload.positiveObjects.isNotEmpty()) {
            appendLine("正向对象：${payload.positiveObjects.sorted().joinToString("、")}")
        }
        if (payload.positiveActions.isNotEmpty()) {
            appendLine("正向动作：${payload.positiveActions.sorted().joinToString("、")}")
        }
        if (payload.requiredSlots.isNotEmpty()) {
            appendLine("必填槽位：${payload.requiredSlots.sorted().joinToString("、")}")
        }
        // CR-019：温度操作语义与数值角色边界（adjust/set 区分证据）。
        if (payload.temperatureOperationSemantics.isNotEmpty()) {
            appendLine("温度操作语义：${payload.temperatureOperationSemantics.sorted().joinToString("、")}")
        }
        if (payload.temperatureValueRoles.isNotEmpty()) {
            appendLine("温度数值角色：${payload.temperatureValueRoles.sorted().joinToString("、")}")
        }
        if (payload.parameterSchemas.isNotEmpty()) {
            appendLine("参数：${payload.parameterSchemas.joinToString("；") { renderParameter(it) }}")
        }
        if (payload.approvedAliases.isNotEmpty()) {
            appendLine(
                "位置别名：${payload.approvedAliases.values.flatten().distinct().sorted().joinToString("、")}"
            )
        }
        if (payload.positiveExamples.isNotEmpty()) {
            appendLine("正例：${payload.positiveExamples.joinToString("；")}")
        }
        if (payload.negativeBoundaries.isNotEmpty()) {
            appendLine("负例：${payload.negativeBoundaries.joinToString("；")}")
        }
        if (payload.conflictingToolIds.isNotEmpty()) {
            appendLine("相似工具：${payload.conflictingToolIds.sorted().joinToString("、")}")
        }
        if (payload.sourceVersions.isNotEmpty()) {
            appendLine("来源版本：${payload.sourceVersions.sorted().joinToString(",")}")
        }
    }

    private fun renderParameter(schema: RetrievalParameterSchema): String {
        val sb = StringBuilder(schema.name)
        if (schema.required) sb.append("!")
        sb.append(":").append(schema.type)
        if (schema.enum.isNotEmpty()) sb.append("[").append(schema.enum.sorted().joinToString("|")).append("]")
        schema.defaultValue?.let { sb.append("=default:").append(it) }
        return sb.toString()
    }

    /** 兼容入口：从 ToolDefinition 直接构建载荷后渲染。 */
    fun tool(tool: ToolDefinition, conflictToolIds: Set<String> = emptySet(), sourceVersions: Set<String> = emptySet()): String =
        tool(ToolRetrievalPayloadFactory.fromTool(tool, conflictToolIds, sourceVersions))

    fun workflow(wf: WorkflowDefinition): String = buildString {
        appendLine("场景：${wf.name}")
        if (wf.description.isNotBlank()) appendLine("说明：${wf.description}")
        if (wf.triggerExamples.isNotEmpty()) appendLine("触发：${wf.triggerExamples.joinToString("；")}")
        appendLine("步骤：${wf.steps.joinToString("；") { it.stepId }}")
    }

    /** 从参数 JSON Schema 文本提取对象/属性名。 */
    fun objectsFromSchema(schema: String): String {
        val names = Regex("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:")
            .findAll(schema).map { it.groupValues[1] }.toList()
        return names.distinct().take(8).joinToString("、")
    }

    /** 压缩 Schema：去空白与换行。 */
    fun compactSchema(schema: String): String =
        schema.replace(Regex("\\s+"), "").take(200)
}
