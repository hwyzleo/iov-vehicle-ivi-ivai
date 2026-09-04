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

/** Converts plain Kotlin values back into JSON elements (L0 candidate → AgentOutput). */
fun valueToJsonElement(value: Any?): JsonElement = when (value) {
    is JsonElement -> value
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    null -> kotlinx.serialization.json.JsonNull
    else -> JsonPrimitive(value.toString())
}

fun valuesToJsonArgs(arguments: Map<String, Any?>): Map<String, JsonElement> =
    arguments.mapValues { (_, value) -> valueToJsonElement(value) }
