package net.hwyz.iov.vehicle.ivi.ivai.agenttest.result

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JsonElement ↔ Map<String, Any?> 转换（IVI-IVAI-DSN-CR-014 导出参数类型语义）。
 * 保留嵌套对象、数组、布尔值、数字、null 与字符串类型。
 */
object JsonValueMapper {

    fun toValueMap(json: JsonObject?): Map<String, Any?> =
        json?.entries?.associate { (k, v) -> k to toValue(v) } ?: emptyMap()

    fun toValue(element: JsonElement?): Any? = when (element) {
        null -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" -> true
            element.content == "false" -> false
            element.content == "null" -> null
            else -> element.content.toLongOrNull()
                ?: element.content.toDoubleOrNull()
                ?: element.content
        }
        is JsonObject -> element.entries.associate { (k, v) -> k to toValue(v) }
        is JsonArray -> element.map { toValue(it) }
        else -> element.toString()
    }
}
