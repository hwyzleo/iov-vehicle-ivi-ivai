package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-009 / CR-013 验证设计 · 治理 Tool → 运行时 ToolDefinition 的参数 Schema 透传。
 *
 * 回归场景 REGEN-005：L1 路径要求模型能从候选 Tool 的 Schema 中读到 level 枚举
 * （OFF/LOW/STANDARD/HIGH），此前 toToolDefinition 写死空 Schema 导致模型只能输出
 * 空参数。本测试在确定性层验证（无需 LLM）：
 *  - 全部 160 个治理 Tool 的声明参数都保留在展开后的 Schema 中。
 *  - vehicle.brake_regen.set 的 level 携带批准枚举且为必填。
 *  - L0 批准正例挂载到无画像的治理 Tool（供 L1 Prompt / 检索文档使用）。
 */
class GovernanceToolSchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `全部治理 Tool 运行时 Schema 保留声明参数且必填与可选一致`() {
        for (spec in ToolCatalogV1.ALL) {
            val def = GovernanceWorkspace.toToolDefinition(spec)
            val schema = json.parseToJsonElement(def.parameterSchema).jsonObject
            val props = schema["properties"]!!.jsonObject
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val declared = extractDeclared(spec.parameterSchema)
            for ((name, isOptional, typePart) in declared) {
                assertTrue(name in props, "${spec.toolId} 展开后缺少参数 $name")
                val expectRequired = !isOptional && !GovernanceSchemaParser.isFixedConstant(typePart)
                assertEquals(expectRequired, name in required, "${spec.toolId} 参数 $name 必填标记不一致")
            }
        }
    }

    @Test
    fun `REGEN-005 回归 - brake_regen level 枚举包含 HIGH 且必填`() {
        val spec = ToolCatalogV1.get("vehicle.brake_regen.set")!!
        val def = GovernanceWorkspace.toToolDefinition(spec)
        val schema = json.parseToJsonElement(def.parameterSchema).jsonObject
        val level = schema["properties"]!!.jsonObject["level"]!!.jsonObject
        assertEquals("string", level["type"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("OFF", "LOW", "STANDARD", "HIGH"),
            level["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertTrue("level" in schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        // L1 Prompt 渲染的是完整 Schema（含枚举），确保 compact 文本不截断枚举
        assertTrue(def.parameterSchema.contains("\"HIGH\""), def.parameterSchema)
    }

    @Test
    fun `L0 批准正例挂载到无画像治理 Tool`() {
        val def = GovernanceWorkspace.toToolDefinition(ToolCatalogV1.get("vehicle.brake_regen.set")!!)
        assertTrue(def.positiveExamples.contains("设置能量回收为高"), def.positiveExamples.toString())
        assertTrue(def.positiveExamples.contains("把能量回收调到最强"), def.positiveExamples.toString())
        assertTrue(def.negativeExamples.isNotEmpty(), def.negativeExamples.toString())
    }

    @Test
    fun `registerAllStubs 注册的 brake_regen 携带 level 枚举`() {
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        val def = registry.get("vehicle.brake_regen.set")!!
        assertTrue(def.parameterSchema.contains("\"level\""), def.parameterSchema)
        assertTrue(def.parameterSchema.contains("\"HIGH\""), def.parameterSchema)
    }

    /** 提取 参数名 → 是否必填 → 类型记法。 */
    private fun extractDeclared(compact: String): List<Triple<String, Boolean, String>> {
        val body = compact.trim().removePrefix("{").removeSuffix("}").trim()
        if (body.isEmpty()) return emptyList()
        return body.split(',').mapNotNull { raw ->
            val param = raw.trim()
            if (param.isEmpty()) return@mapNotNull null
            val colon = param.indexOf(':')
            val namePart = (if (colon >= 0) param.substring(0, colon) else param).trim()
            val typePart = if (colon >= 0) param.substring(colon + 1).trim() else ""
            val optional = namePart.endsWith("?")
            Triple(namePart.removeSuffix("?").trim(), optional, typePart)
        }
    }
}
