package net.hwyz.iov.vehicle.ivi.ivai.agenttest.canonical

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ParameterSchema
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.PropertySchema
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-012 参数规范化单测（IVAI-REQ-118 / IVAI-TEST-007）：
 * 对象键顺序、数值等价、枚举、单位、集合顺序、缺失/null/空值和 Schema 容差。
 */
class ParameterCanonicalizerTest {

    private val obj = JsonObject
    private fun num(n: Number) = JsonPrimitive(n)

    @Test
    fun `整数 2 与无精度损失浮点 2_0 等价`() {
        assertTrue(ParameterCanonicalizer.match(num(2), num(2.0)))
        assertTrue(ParameterCanonicalizer.match(num(2.0), num(2)))
        assertTrue(ParameterCanonicalizer.match(num(2.5), num(2.50)))
        assertFalse(ParameterCanonicalizer.match(num(2), num(3)))
    }

    @Test
    fun `字符串精确比较 不做大小写模糊`() {
        assertTrue(ParameterCanonicalizer.match(JsonPrimitive("driver"), JsonPrimitive("driver")))
        assertFalse(ParameterCanonicalizer.match(JsonPrimitive("driver"), JsonPrimitive("DRIVER")))
        assertFalse(ParameterCanonicalizer.match(JsonPrimitive("driver"), JsonPrimitive("driv")))
    }

    @Test
    fun `字符串与数值类型不同 默认不匹配`() {
        assertFalse(ParameterCanonicalizer.match(JsonPrimitive("2"), num(2)))
        assertFalse(ParameterCanonicalizer.match(num(2), JsonPrimitive("2")))
    }

    @Test
    fun `Schema 数值类型时 数值字符串与单位后缀规范化`() {
        val numeric = PropertySchema(type = "number")
        // 数值字符串（"2"）与数值等价。
        assertTrue(ParameterCanonicalizer.match(JsonPrimitive("2"), num(2), numeric))
        // 单位后缀剥离：℃ / 度 / 摄氏度。
        assertTrue(ParameterCanonicalizer.match(JsonPrimitive("26℃"), num(26), numeric))
        assertTrue(ParameterCanonicalizer.match(JsonPrimitive("26度"), num(26), numeric))
        assertTrue(ParameterCanonicalizer.match(JsonPrimitive("26摄氏度"), num(26), numeric))
        assertFalse(ParameterCanonicalizer.match(JsonPrimitive("26℃"), num(27), numeric))
    }

    @Test
    fun `布尔严格比较`() {
        assertTrue(ParameterCanonicalizer.match(JsonPrimitive(true), JsonPrimitive(true)))
        assertFalse(ParameterCanonicalizer.match(JsonPrimitive(true), JsonPrimitive(false)))
    }

    @Test
    fun `null 与空串 与缺失语义不同`() {
        assertTrue(ParameterCanonicalizer.match(JsonNull, JsonNull))
        assertFalse(ParameterCanonicalizer.match(JsonNull, JsonPrimitive("")))
        assertFalse(ParameterCanonicalizer.match(JsonNull, JsonPrimitive("null")))
    }

    @Test
    fun `数组默认有序比较`() {
        val a = buildJsonArray { add(JsonPrimitive("front")); add(JsonPrimitive("rear")) }
        val b = buildJsonArray { add(JsonPrimitive("front")); add(JsonPrimitive("rear")) }
        val c = buildJsonArray { add(JsonPrimitive("rear")); add(JsonPrimitive("front")) }
        assertTrue(ParameterCanonicalizer.match(a, b))
        assertFalse(ParameterCanonicalizer.match(a, c))
        assertFalse(ParameterCanonicalizer.match(a, buildJsonArray { add(JsonPrimitive("front")) }))
    }

    @Test
    fun `对象键顺序不影响结果`() {
        val e = buildJsonObject { put("zone", "driver"); put("step", 2) }
        val a = buildJsonObject { put("step", 2); put("zone", "driver") }
        assertTrue(ParameterCanonicalizer.match(e, a))
    }

    @Test
    fun `嵌套对象递归比较 允许实际附加键`() {
        val e = buildJsonObject {
            put("schedule", buildJsonObject { put("hour", 18) })
        }
        val a = buildJsonObject {
            put("schedule", buildJsonObject { put("hour", 18); put("minute", 0) })
        }
        assertTrue(ParameterCanonicalizer.match(e, a))
        val wrong = buildJsonObject {
            put("schedule", buildJsonObject { put("hour", 19) })
        }
        assertFalse(ParameterCanonicalizer.match(e, wrong))
    }

    @Test
    fun `argumentsMatch - 空预期仅匹配空实际 并忽略元数据差异由调用方保证`() {
        assertTrue(ParameterCanonicalizer.argumentsMatch(JsonObject(emptyMap()), JsonObject(emptyMap())))
        assertTrue(ParameterCanonicalizer.argumentsMatch(JsonObject(emptyMap()), null))
        assertFalse(
            ParameterCanonicalizer.argumentsMatch(
                JsonObject(emptyMap()),
                buildJsonObject { put("enabled", true) }
            )
        )
    }

    @Test
    fun `argumentsMatch - 逐字段断言 允许实际附加字段 缺失即失败`() {
        val expected = buildJsonObject {
            put("zone", "driver")
            put("step", 2)
        }
        val actualWithExtra = buildJsonObject {
            put("zone", "driver")
            put("step", 2)
            put("traceId", "t-1")
        }
        assertTrue(ParameterCanonicalizer.argumentsMatch(expected, actualWithExtra))

        val missing = buildJsonObject { put("zone", "driver") }
        assertFalse(ParameterCanonicalizer.argumentsMatch(expected, missing))

        val wrongValue = buildJsonObject {
            put("zone", "driver")
            put("step", 3)
        }
        assertFalse(ParameterCanonicalizer.argumentsMatch(expected, wrongValue))
    }

    @Test
    fun `argumentsMatch - 数值等价（2 vs 2_0）通过`() {
        val expected = buildJsonObject { put("step", 2) }
        val actual = buildJsonObject { put("step", 2.0) }
        assertTrue(ParameterCanonicalizer.argumentsMatch(expected, actual))
    }

    @Test
    fun `argumentsMatch - Schema 数值类型时 单位后缀规范化`() {
        val expected = buildJsonObject { put("temperature", 26) }
        val actual = buildJsonObject { put("temperature", "26℃") }
        val schema = ParameterSchema(
            type = "object",
            properties = mapOf("temperature" to PropertySchema(type = "number")),
            required = listOf("temperature")
        )
        assertTrue(ParameterCanonicalizer.argumentsMatch(expected, actual, schema))
        // 无 Schema 时字符串与数值不匹配。
        assertFalse(ParameterCanonicalizer.argumentsMatch(expected, actual, null))
    }

    @Test
    fun `浮点容差 - 无 Schema 定义时精确十进制比较`() {
        assertFalse(ParameterCanonicalizer.match(num(0.1), num(0.2)))
        assertTrue(ParameterCanonicalizer.match(num(0.1), num(0.10)))
    }
}
