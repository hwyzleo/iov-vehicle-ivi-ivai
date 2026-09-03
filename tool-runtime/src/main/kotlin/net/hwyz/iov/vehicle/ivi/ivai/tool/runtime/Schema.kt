package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parsed representation of a tool's parameter JSON Schema (subset supported by v0.1).
 */
data class ParameterSchema(
    val type: String,
    val properties: Map<String, PropertySchema>,
    val required: List<String>
)

/**
 * A single property constraint. [defaultValue] is the schema default (a JSON primitive).
 */
data class PropertySchema(
    val type: String,
    val enum: List<String>? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val defaultValue: JsonPrimitive? = null
)

/**
 * Parses the raw JSON Schema text stored in a ToolDefinition into [ParameterSchema].
 */
object SchemaParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(schemaJson: String): ParameterSchema {
        val root = json.parseToJsonElement(schemaJson).jsonObject
        val type = root["type"]?.jsonPrimitive?.content ?: "object"
        val required = root["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val properties = root["properties"]?.jsonObject?.mapValues { (_, value) ->
            val obj = value.jsonObject
            PropertySchema(
                type = obj["type"]?.jsonPrimitive?.content ?: "string",
                enum = obj["enum"]?.jsonArray?.map { it.jsonPrimitive.content },
                minimum = obj["minimum"]?.jsonPrimitive?.doubleOrNull,
                maximum = obj["maximum"]?.jsonPrimitive?.doubleOrNull,
                defaultValue = obj["default"] as? JsonPrimitive
            )
        } ?: emptyMap()
        return ParameterSchema(type = type, properties = properties, required = required)
    }
}
