package net.hwyz.iov.vehicle.ivi.ivai.agenttest.canonical

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-019 参数展示规范化（IVAI-TEST-COMPARATOR-001 校验项）：
 * 参数展示与评分共用同一 Schema-aware Comparator 的 canonical 值——
 * 数值 5 与 5.0 等价，导出列不得显示不一致。
 */
class ParameterCanonicalizerDisplayTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun canonical(raw: String): JsonElement =
        ParameterCanonicalizer.canonicalForDisplay(json.parseToJsonElement(raw))!!

    @Test
    fun `整数与浮点等价展示`() {
        assertEquals("5", canonical("5.0").jsonPrimitive.content)
        assertEquals("5", canonical("5").jsonPrimitive.content)
        assertEquals("5", canonical("5.00").jsonPrimitive.content)
    }

    @Test
    fun `非整数保留精度`() {
        assertEquals("0.5", canonical("0.5").jsonPrimitive.content)
        assertEquals("5.5", canonical("5.5").jsonPrimitive.content)
    }

    @Test
    fun `嵌套对象与数组递归规范化`() {
        val obj = canonical("""{"level":5.0,"nested":{"step":1.0},"list":[2.0,3]}""")
        assertEquals(
            """{"level":5,"nested":{"step":1},"list":[2,3]}""",
            obj.toString().replace(" ", "")
        )
    }

    @Test
    fun `字符串与布尔原样保留`() {
        assertEquals("\"driver\"", canonical("\"driver\"").toString())
        assertEquals("true", canonical("true").toString())
        assertEquals("\"5.0\"", canonical("\"5.0\"").toString(), "字符串不应被数值化")
    }

    @Test
    fun `与评分比较器同源`() {
        // 展示 canonical 后 5.0→5，评分断言 5==5.0 亦通过（同一 Schema-aware Comparator 语义）。
        val expected = json.parseToJsonElement("""{"level":5.0}""")
        val actual = json.parseToJsonElement("""{"level":5}""")
        val expectedObj = expected.jsonObject
        val actualObj = actual.jsonObject
        assertTrue(ParameterCanonicalizer.argumentsMatch(expectedObj, actualObj))
        val expectedDisplay = ParameterCanonicalizer.canonicalForDisplay(expectedObj)!!.jsonObject
        val actualDisplay = ParameterCanonicalizer.canonicalForDisplay(actualObj)!!.jsonObject
        assertEquals(expectedDisplay, actualDisplay, "展示值一致才满足 IVAI-TEST-COMPARATOR-001")
    }
}
