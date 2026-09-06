package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition

/**
 * L1 语义文本模板（CR-011）。
 *
 * semanticText 按固定模板拼接名称、职责、动作、对象、参数语义、枚举语义和
 * 批准 Alias，用于 Embedding 召回。不得将 Policy 密钥、真实 Binding 地址、
 * 用户敏感数据或任意未审核语料写入 Tool 检索文档。
 */
object SemanticTextTemplate {

    fun tool(tool: ToolDefinition): String = buildString {
        appendLine("工具：${tool.name}")
        appendLine("职责：${tool.description}")
        appendLine("动作：${tool.positiveExamples.joinToString("；")}")
        appendLine("对象：${objectsFromSchema(tool.parameterSchema)}")
        appendLine("参数：${compactSchema(tool.parameterSchema)}")
        val aliases = (tool.aliases.mapNotNull { it.aliasId } + tool.functionId?.let { listOf(it) }.orEmpty())
            .distinct()
        if (aliases.isNotEmpty()) {
            appendLine("别名：${aliases.joinToString("；")}")
        }
    }

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
