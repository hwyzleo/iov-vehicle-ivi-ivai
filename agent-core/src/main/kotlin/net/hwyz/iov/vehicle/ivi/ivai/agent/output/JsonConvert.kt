package net.hwyz.iov.vehicle.ivi.ivai.agent.output

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Converts JSON argument elements into plain Kotlin values for the tool runtime.
 */
fun jsonElementToValue(element: JsonElement): Any? = when (element) {
    is JsonObject -> element.toString()
    is JsonArray -> element.toString()
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.content == "true" -> true
        element.content == "false" -> false
        else -> element.doubleOrNull ?: element.content
    }
    else -> null
}

fun jsonArgsToValues(arguments: Map<String, JsonElement>): Map<String, Any?> =
    arguments.mapValues { (_, value) -> jsonElementToValue(value) }
