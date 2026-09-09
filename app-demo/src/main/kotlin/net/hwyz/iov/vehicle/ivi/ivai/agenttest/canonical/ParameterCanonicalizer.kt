package net.hwyz.iov.vehicle.ivi.ivai.agenttest.canonical

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ParameterSchema
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.PropertySchema

/**
 * Schema 感知的参数规范化比较（IVI-IVAI-DSN-CR-012 / IVAI-REQ-118）。
 *
 * 优先复用 Tool/Workflow Schema 与运行时规范化语义（与 ToolValidator 一致：
 * 数值字符串、整数/浮点强转、℃/度 单位后缀剥离），**不维护测试专用枚举映射**。
 *
 * 规则：
 *  - 整数 2 与无精度损失的浮点 2.0 视为相同（BigDecimal 比较）。
 *  - 字符串按 Schema canonical 名精确比较，不做任意大小写模糊匹配。
 *  - 数值型字符串先剥离单位后缀（℃/度/摄氏度）再比较。
 *  - 数组默认保持顺序；仅当 Schema 标记为集合时忽略顺序并去重（当前 Schema 子集
 *    尚未声明集合类型，首期按有序比较）。
 *  - JSON 对象键顺序不影响结果；嵌套对象对预期声明的键逐项断言，允许实际附加键。
 *  - null、字段缺失与空字符串语义不同，除非 Schema 明确定义等价。
 *  - 浮点容差由具体参数 Schema 定义；无定义时执行精确十进制比较。
 */
object ParameterCanonicalizer {

    /**
     * 断言 [expected] 与 [actual] 两个 JSON 值在 canonical 语义下等价。
     * [property] 为对应参数的 Schema 约束（数值类型 / 枚举），可为 null。
     */
    fun match(expected: JsonElement?, actual: JsonElement?, property: PropertySchema? = null): Boolean {
        if (expected == null || actual == null) return expected == actual

        // null 语义：JsonNull 与 JsonNull 相等，与空串 / 缺失（调用方处理）不同。
        if (expected is JsonNull || actual is JsonNull) return expected is JsonNull && actual is JsonNull

        // Schema 数值类型：数值字符串 / 单位后缀先规范化再比较（与 ToolValidator 一致）。
        if (property?.type == "number" || property?.type == "integer") {
            val e = numericValue(expected)
            val a = numericValue(actual)
            if (e != null && a != null) return e.compareTo(a) == 0
            return false
        }

        return when {
            expected is JsonObject && actual is JsonObject -> objectsMatch(expected, actual)
            expected is JsonArray && actual is JsonArray -> arraysMatch(expected, actual)
            expected is JsonPrimitive && actual is JsonPrimitive -> primitivesMatch(expected, actual)
            else -> false
        }
    }

    /**
     * 断言 [expected] 声明的全部业务参数在 [actual] 中 canonical 匹配。
     *  - expected 空对象表示「预期无业务参数」：仅当实际业务参数也为空时匹配。
     *  - 实际允许包含未在预期中声明的附加字段（诊断元数据等）。
     *  - [schema] 为 Tool 参数 Schema（可为 null；治理 Tool 首期为空 Schema）。
     */
    fun argumentsMatch(expected: JsonObject, actual: JsonObject?, schema: ParameterSchema? = null): Boolean {
        val actualObject = actual ?: JsonObject(emptyMap())
        if (expected.isEmpty()) return actualObject.isEmpty()
        return expected.all { (key, expectedValue) ->
            val actualValue = actualObject[key] ?: return false
            match(expectedValue, actualValue, schema?.properties?.get(key))
        }
    }

    // ------------------------------------------------------------------ helpers

    /** 数值规范化：字符串剥离 ℃/度/摄氏度 后缀后转 BigDecimal；数值直接解析。 */
    private fun numericValue(element: JsonElement): BigDecimal? = when (element) {
        is JsonPrimitive -> {
            val raw = element.content
            if (element.isString) {
                // 先剥最长后缀（摄氏度），再 ℃ / 度，避免误剥。
                raw.trim().removeSuffix("摄氏度").removeSuffix("℃").removeSuffix("度").trim()
                    .toBigDecimalOrNull()
            } else {
                raw.toBigDecimalOrNull()
            }
        }
        else -> null
    }

    private fun primitivesMatch(e: JsonPrimitive, a: JsonPrimitive): Boolean {
        // 字符串与数值类型不同：除非 Schema 数值类型已在上层处理，否则视为不匹配
        // （null / 缺失 / 空串语义也不同）。
        if (e.isString != a.isString) return false
        if (e.isString) return e.content == a.content
        // 两者均为非字符串字面量：布尔严格相等；数值按 BigDecimal 比较（2 == 2.0）。
        if (e.content == "true" || e.content == "false" || a.content == "true" || a.content == "false") {
            return e.content == a.content
        }
        val eb = e.content.toBigDecimalOrNull()
        val ab = a.content.toBigDecimalOrNull()
        return if (eb != null && ab != null) eb.compareTo(ab) == 0 else e.content == a.content
    }

    private fun arraysMatch(e: JsonArray, a: JsonArray): Boolean {
        if (e.size != a.size) return false
        return e.zip(a).all { (x, y) -> match(x, y, null) }
    }

    private fun objectsMatch(e: JsonObject, a: JsonObject): Boolean =
        e.all { (key, value) ->
            a[key]?.let { match(value, it, null) } ?: false
        }

    /**
     * CR-019（IVAI-TEST-COMPARATOR-001）：参数展示与评分共用同一 Schema-aware
     * Comparator 的 canonical 值——整数 5 与 5.0 等价，导出列不得显示不一致。
     * 递归把数值字面量按 BigDecimal 规范化（5.0 → 5）；字符串/布尔/对象/数组
     * 原样保留。
     */
    fun canonicalForDisplay(value: JsonElement?): JsonElement? = when (value) {
        null -> null
        is JsonPrimitive -> {
            if (value.isString || value.content == "true" || value.content == "false") {
                value
            } else {
                val number = value.content.toBigDecimalOrNull()
                if (number != null) canonicalNumber(number) else value
            }
        }
        is JsonObject -> JsonObject(
            value.mapValues { (_, v) -> canonicalForDisplay(v) ?: JsonNull }
        )
        is JsonArray -> JsonArray(value.map { canonicalForDisplay(it) ?: JsonNull })
        else -> value
    }

    /** 数值展示规范化：整数 5.0 → 5；非整数保留原精度（5.5）。 */
    private fun canonicalNumber(number: BigDecimal): JsonPrimitive {
        val stripped = number.stripTrailingZeros()
        return if (stripped.scale() <= 0) {
            JsonPrimitive(stripped.toLong())
        } else {
            JsonPrimitive(stripped)
        }
    }
}
