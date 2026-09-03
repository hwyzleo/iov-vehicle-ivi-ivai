package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Whitelist + parameter schema validation and default / unit normalization.
 */
class ToolValidator(
    private val registry: ToolRegistry,
    private val schemaParser: SchemaParser = SchemaParser
) {

    fun validateToolId(toolId: String): ToolValidationIssue? =
        if (registry.get(toolId) == null) {
            ToolValidationIssue(
                kind = ValidationIssueKind.UNKNOWN_TOOL,
                toolId = toolId,
                argument = null,
                message = "Unknown tool id: $toolId"
            )
        } else {
            null
        }

    fun validateArguments(tool: ToolDefinition, args: Map<String, Any?>): List<ToolValidationIssue> {
        val schema = schemaParser.parse(tool.parameterSchema)
        val issues = mutableListOf<ToolValidationIssue>()

        for (key in schema.required) {
            if (args[key] == null) {
                issues += ToolValidationIssue(
                    kind = ValidationIssueKind.MISSING_ARGUMENT,
                    toolId = tool.toolId,
                    argument = key,
                    message = "Missing required argument: $key"
                )
            }
        }

        for ((key, prop) in schema.properties) {
            val raw = args[key] ?: continue
            when (prop.type) {
                "number", "integer" -> {
                    val value = parseNumber(raw)
                    if (value == null) {
                        issues += ToolValidationIssue(
                            kind = ValidationIssueKind.INVALID_ARGUMENT_TYPE,
                            toolId = tool.toolId,
                            argument = key,
                            message = "Argument $key must be a number, got: $raw"
                        )
                    } else {
                        prop.minimum?.let { min ->
                            if (value < min) {
                                issues += ToolValidationIssue(
                                    kind = ValidationIssueKind.OUT_OF_RANGE,
                                    toolId = tool.toolId,
                                    argument = key,
                                    message = "Argument $key=$value below minimum $min"
                                )
                            }
                        }
                        prop.maximum?.let { max ->
                            if (value > max) {
                                issues += ToolValidationIssue(
                                    kind = ValidationIssueKind.OUT_OF_RANGE,
                                    toolId = tool.toolId,
                                    argument = key,
                                    message = "Argument $key=$value above maximum $max"
                                )
                            }
                        }
                    }
                }
                "string" -> {
                    val value = raw as? String
                    if (value == null) {
                        issues += ToolValidationIssue(
                            kind = ValidationIssueKind.INVALID_ARGUMENT_TYPE,
                            toolId = tool.toolId,
                            argument = key,
                            message = "Argument $key must be a string, got: $raw"
                        )
                    } else if (prop.enum != null && value !in prop.enum) {
                        issues += ToolValidationIssue(
                            kind = ValidationIssueKind.NOT_IN_ENUM,
                            toolId = tool.toolId,
                            argument = key,
                            message = "Argument $key=$value not in ${prop.enum}"
                        )
                    }
                }
                "boolean" -> {
                    if (raw !is Boolean) {
                        issues += ToolValidationIssue(
                            kind = ValidationIssueKind.INVALID_ARGUMENT_TYPE,
                            toolId = tool.toolId,
                            argument = key,
                            message = "Argument $key must be a boolean, got: $raw"
                        )
                    }
                }
            }
        }

        return issues
    }

    /**
     * Fills schema defaults and normalizes values (numeric strings, "℃"/"度" suffixes,
     * integer vs number coercion) before execution.
     */
    fun applyDefaultsAndNormalize(tool: ToolDefinition, args: Map<String, Any?>): Map<String, Any?> {
        val schema = schemaParser.parse(tool.parameterSchema)
        val result = LinkedHashMap<String, Any?>(args)

        for ((key, prop) in schema.properties) {
            val raw = result[key]
            if (raw == null) {
                prop.defaultValue?.let { default ->
                    result[key] = primitiveToValue(default, prop.type)
                }
                continue
            }
            when (prop.type) {
                "integer" -> {
                    parseNumber(raw)?.let { result[key] = it.toInt() }
                }
                "number" -> {
                    parseNumber(raw)?.let { result[key] = it }
                }
            }
        }
        return result
    }

    private fun parseNumber(raw: Any?): Double? = when (raw) {
        is Number -> raw.toDouble()
        is String -> parseNumberString(raw)
        else -> null
    }

    private fun parseNumberString(raw: String): Double? {
        val cleaned = raw.trim()
            .removeSuffix("℃")
            .removeSuffix("度")
            .trim()
        return cleaned.toDoubleOrNull()
    }

    private fun primitiveToValue(primitive: kotlinx.serialization.json.JsonPrimitive, type: String): Any? {
        if (primitive.isString) {
            return when (type) {
                "number" -> primitive.content.toDoubleOrNull() ?: primitive.content
                "integer" -> primitive.content.toIntOrNull() ?: primitive.content
                "boolean" -> primitive.content.toBooleanStrictOrNull() ?: primitive.content
                else -> primitive.content
            }
        }
        val long = primitive.longOrNull
        val double = primitive.doubleOrNull
        return when {
            long != null && (type == "integer" || double == long.toDouble()) -> long.toInt()
            double != null -> double
            else -> primitive.content
        }
    }
}
