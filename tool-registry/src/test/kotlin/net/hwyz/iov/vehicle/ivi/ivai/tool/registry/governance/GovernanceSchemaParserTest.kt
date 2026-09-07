package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-009 验证设计 · 治理紧凑参数记法 → JSON Schema 展开器。
 *
 * 覆盖目录全部记法形态：必填/可选、int/number 范围、字面枚举、泛型 enum +
 * 批准值、无类型参数、固定单位 token、裸范围、boolean/array/object/any、空 schema。
 */
class GovernanceSchemaParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(compact: String, enumValues: Map<String, List<String>> = emptyMap()): JsonObject =
        json.parseToJsonElement(GovernanceSchemaParser.parse(compact, enumValues)).jsonObject

    @Test
    fun `空 schema 保持空对象且必填为空`() {
        val schema = parse("{}")
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertEquals(0, schema["properties"]?.jsonObject?.size)
        assertEquals(0, schema["required"]?.jsonArray?.size)
    }

    @Test
    fun `必填与可选参数按声明顺序展开`() {
        val schema = parse("{level:enum, position?:enum, zone?:enum}")
        val props = schema["properties"]!!.jsonObject
        assertEquals(listOf("level", "position", "zone"), props.keys.toList())
        assertEquals(listOf("level"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `int 与 number 范围展开为 minimum maximum`() {
        val schema = parse("{temperature:number[16..30], step:int[1..3], percent:int[50..100]}")
        val props = schema["properties"]!!.jsonObject
        assertEquals("number", props["temperature"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(16.0, props["temperature"]!!.jsonObject["minimum"]!!.jsonPrimitive.content.toDouble())
        assertEquals(30.0, props["temperature"]!!.jsonObject["maximum"]!!.jsonPrimitive.content.toDouble())
        assertEquals("integer", props["step"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("integer", props["percent"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `字面枚举按声明顺序保留`() {
        val schema = parse("{mode:face|feet|defrost|mixed}")
        val mode = schema["properties"]!!.jsonObject["mode"]!!.jsonObject
        assertEquals("string", mode["type"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("face", "feet", "defrost", "mixed"),
            mode["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `泛型 enum 使用 L0 批准枚举值`() {
        val schema = parse(
            "{level:enum}",
            mapOf("level" to listOf("\"OFF\"", "\"LOW\"", "\"STANDARD\"", "\"HIGH\""))
        )
        val level = schema["properties"]!!.jsonObject["level"]!!.jsonObject
        assertEquals(
            listOf("OFF", "LOW", "STANDARD", "HIGH"),
            level["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `泛型 enum 无批准值时保持 string 且不携带枚举`() {
        val schema = parse("{mode:enum}")
        val mode = schema["properties"]!!.jsonObject["mode"]!!.jsonObject
        assertEquals("string", mode["type"]?.jsonPrimitive?.content)
        assertFalse(mode.containsKey("enum"))
    }

    @Test
    fun `无类型参数默认 string`() {
        val schema = parse("{startTime,endTime?,repeat?}")
        val props = schema["properties"]!!.jsonObject
        assertEquals("string", props["startTime"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("string", props["endTime"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(listOf("startTime"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `固定单位 token 视为固定常量且不参与必填`() {
        val schema = parse("{speed:number,unit:kmh}")
        val unit = schema["properties"]!!.jsonObject["unit"]!!.jsonObject
        assertEquals(listOf("kmh"), unit["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        // 固定常量是工具固有属性，不是模型必须提供的输入
        assertEquals(listOf("speed"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        // 目录真实形态：空调目标温度固定摄氏度
        val zoneSchema = parse("{zone:enum, temperature:number[16..30], unit:C}")
        val unitC = zoneSchema["properties"]!!.jsonObject["unit"]!!.jsonObject
        assertEquals(listOf("C"), unitC["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("zone", "temperature"), zoneSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `裸范围视为 number 范围`() {
        val schema = parse("{target:open|close|vent,percentage?:0..100}")
        val percentage = schema["properties"]!!.jsonObject["percentage"]!!.jsonObject
        assertEquals("number", percentage["type"]?.jsonPrimitive?.content)
        assertEquals(0.0, percentage["minimum"]!!.jsonPrimitive.content.toDouble())
        assertEquals(100.0, percentage["maximum"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `boolean array object any 类型`() {
        val schema = parse("{enabled:boolean, items:array, config?:object, value:any}")
        val props = schema["properties"]!!.jsonObject
        assertEquals("boolean", props["enabled"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("array", props["items"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("object", props["config"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertFalse(props["value"]!!.jsonObject.containsKey("type"))
    }

    @Test
    fun `全部 160 个治理 Tool 的紧凑 schema 均可展开为合法 JSON Schema`() {
        for (spec in ToolCatalogV1.ALL) {
            val expanded = GovernanceSchemaParser.parse(spec.parameterSchema)
            val schema = json.parseToJsonElement(expanded).jsonObject
            assertEquals("object", schema["type"]?.jsonPrimitive?.content, "${spec.toolId} 顶层必须是 object")
            val declared = extractDeclaredNames(spec.parameterSchema)
            val props = schema["properties"]!!.jsonObject
            for (name in declared) {
                assertTrue(name in props, "${spec.toolId} 展开后缺少参数 $name（$expanded）")
            }
        }
    }

    /** 从紧凑记法提取声明的参数名（去掉可选标记 ?）。 */
    private fun extractDeclaredNames(compact: String): List<String> {
        val body = compact.trim().removePrefix("{").removeSuffix("}").trim()
        if (body.isEmpty()) return emptyList()
        return body.split(',').mapNotNull { raw ->
            val param = raw.trim()
            if (param.isEmpty()) return@mapNotNull null
            val colon = param.indexOf(':')
            val namePart = (if (colon >= 0) param.substring(0, colon) else param).trim()
            namePart.removeSuffix("?").trim()
        }
    }
}
