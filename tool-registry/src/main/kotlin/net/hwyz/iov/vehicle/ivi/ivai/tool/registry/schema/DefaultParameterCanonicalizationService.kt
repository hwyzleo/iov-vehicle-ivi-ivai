package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.CanonicalAliasLexicon

/**
 * 轻量参数 Schema 解析（IVI-IVAI-DSN-CR-016）。
 *
 * 与 tool-runtime 的 SchemaParser 输出同一 JSON Schema 子集语义；tool-registry
 * 不依赖 tool-runtime，故在共享层内自含解析，供 [DefaultParameterCanonicalizationService]
 * 使用，保证 L0/L1/Validator/Scorer 读取同一 canonical 参数约束。
 */
data class CanonicalPropertySchema(
    val name: String,
    val type: String,
    val enum: List<String>? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val defaultValue: JsonElement? = null
)

data class CanonicalParameterSchema(
    val properties: List<CanonicalPropertySchema>,
    val required: List<String>
)

/** 参数 Schema 解析器（tool-registry 内轻量实现）。 */
object CanonicalSchemaParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(schemaJson: String): CanonicalParameterSchema {
        val root = json.parseToJsonElement(schemaJson).jsonObject
        val required = root["required"]?.jsonArray
            ?.mapNotNull { it as? JsonPrimitive }
            ?.map { it.content }
            ?: emptyList()
        val properties = root["properties"]?.jsonObject?.mapNotNull { (name, value) ->
            if (value == JsonNull) return@mapNotNull null
            val obj = value.jsonObject
            CanonicalPropertySchema(
                name = name,
                type = obj["type"]?.jsonPrimitive?.content ?: "string",
                enum = obj["enum"]?.jsonArray?.mapNotNull { it as? JsonPrimitive }?.map { it.content },
                minimum = obj["minimum"]?.jsonPrimitive?.doubleOrNull,
                maximum = obj["maximum"]?.jsonPrimitive?.doubleOrNull,
                defaultValue = obj["default"]
            )
        } ?: emptyList()
        return CanonicalParameterSchema(properties = properties, required = required)
    }
}

/**
 * 默认参数规范化实现（IVI-IVAI-DSN-CR-016）。
 *
 * 处理顺序：参数名 Alias → 类型转换 → 字段级枚举 Alias → 单位转换 → 范围校验 →
 * 必填校验 → 冲突校验 → 稳定 JSON 序列化。
 *
 *  - 枚举只依据当前 Tool Schema 与字段级 Alias 表转换；ALL / all / 全部 / 所有 /
 *    整车 等映射为 Schema 批准的同一 canonical 值；
 *  - 自由文本不被任意 lowercase；
 *  - 范围越界 / 类型非法 / 必填缺失 / 参数名 alias 冲突 → Failed（IVAI-PARAM-001）；
 *  - Alias/Schema 版本不一致由装配方在注入时校验（IVAI-PARAM-002）。
 */
class DefaultParameterCanonicalizationService(
    private val registry: ToolRegistry,
    private val lexicon: CanonicalAliasLexicon
) : ParameterCanonicalizationService {

    override fun canonicalize(
        targetId: String,
        arguments: Map<String, Any?>,
        source: CanonicalizationSource,
        requiredOverride: Set<String>?
    ): CanonicalizationResult {
        val tool = registry.get(targetId)
        if (tool == null) {
            return CanonicalizationResult.Failed(
                errorCode = PARAM_CANONICALIZE,
                message = "未知 Tool/参数目标：$targetId"
            )
        }
        val schema = CanonicalSchemaParser.parse(tool.parameterSchema)
        val propsByName = schema.properties.associateBy { it.name }
        val sources = LinkedHashMap<String, String>()
        val canonical = LinkedHashMap<String, Any?>()

        // 1) 参数名 Alias + 类型转换 + 字段级枚举 Alias + 单位转换。
        for ((rawName, rawValue) in arguments) {
            if (rawValue == null) continue
            val name = lexicon.canonicalParameterName(rawName)
            val prop = propsByName[name]
            if (prop == null) {
                // 参数名 Alias 重映射后仍不在 Schema 内 → 无法 canonicalize。
                if (lexicon.parameterNameAliases.containsKey(rawName)) {
                    return CanonicalizationResult.Failed(
                        errorCode = PARAM_CANONICALIZE,
                        message = "参数名 $rawName 映射到 $name，但不在 ${tool.toolId} Schema 内"
                    )
                }
                // Schema 未知参数：保留原始值（由 ToolValidator 判定非法），
                // 但 canonical 语义不改变它。
                canonical[name] = rawValue
                sources[name] = sourceOf(rawName, rawValue, source)
                continue
            }
            val converted = convert(prop, rawValue)
                ?: return CanonicalizationResult.Failed(
                    errorCode = PARAM_CANONICALIZE,
                    message = "参数 $name 值 $rawValue 无法按类型 ${prop.type} 转换"
                )
            canonical[name] = converted
            sources[name] = sourceOf(rawName, rawValue, source)
        }

        // 2) 范围校验（越界 → IVAI-PARAM-001，禁止生成候选）。
        for ((name, value) in canonical) {
            val prop = propsByName[name] ?: continue
            val number = (value as? Number)?.toDouble()
            if (number != null) {
                prop.minimum?.let { if (number < it) return rangeFailure(name, value, "minimum", it) }
                prop.maximum?.let { if (number > it) return rangeFailure(name, value, "maximum", it) }
            }
        }

        // 3) 必填校验（缺失 → missingArguments，禁止合法候选）。
        val required = requiredOverride ?: schema.required.toSet()
        val missing = required.filter { canonical[it] == null }
        if (missing.isNotEmpty()) {
            return CanonicalizationResult.Failed(
                errorCode = PARAM_CANONICALIZE,
                message = "必填参数缺失：${missing.joinToString(",")}",
                missingArguments = missing
            )
        }

        // 4) 冲突校验：参数名 Alias 重映射使两个不同键映射到同一 canonical 键且值不同。
        val aliasGroups = arguments.keys.groupBy { lexicon.canonicalParameterName(it) }
        for ((canonicalName, rawNames) in aliasGroups) {
            if (rawNames.size <= 1) continue
            val distinct = rawNames.mapNotNull { arguments[it] }.distinct()
            if (distinct.size > 1) {
                return CanonicalizationResult.Failed(
                    errorCode = PARAM_CANONICALIZE,
                    message = "参数名 Alias 冲突：$rawNames 映射到同一参数 $canonicalName 但值不同"
                )
            }
        }

        // 5) 稳定 JSON 序列化（key 排序；Snapshot 只保存 canonical 参数）。
        val stableJson = stableJsonObject(canonical)
        return CanonicalizationResult.Success(
            canonicalArguments = stableJson,
            argumentSources = sources
        )
    }

    private fun rangeFailure(name: String, value: Any?, bound: String, limit: Double): CanonicalizationResult.Failed =
        CanonicalizationResult.Failed(
            errorCode = PARAM_CANONICALIZE,
            message = "参数 $name=$value 越界（$bound=$limit）"
        )

    /** 类型转换：integer/number 字符串→数值；枚举字段→字段级 Alias canonical。 */
    private fun convert(prop: CanonicalPropertySchema, raw: Any?): Any? {
        val canonicalValue = when {
            // 字段级枚举 Alias（ALL→all / 全部→all / 整车→all ...），仅当 Schema
            // enum 或词表登记时才转换，绝不随意 lowercase 自由文本。
            prop.enum != null && raw is String -> {
                val mapped = lexicon.enumCanonical(prop.name, raw)
                if (mapped in prop.enum) mapped else {
                    // 大小写差异且 Schema enum 含全小写：只允许字符串与 enum 成员
                    // 经忽略大小写比对后的映射（受控，非任意 lowercase）。
                    prop.enum.firstOrNull { it.equals(raw, ignoreCase = true) } ?: raw
                }
            }
            else -> raw
        }
        return when (prop.type) {
            "integer" -> toInteger(canonicalValue)
            "number" -> toNumber(canonicalValue)
            "boolean" -> (canonicalValue as? Boolean) ?: canonicalValue
            else -> canonicalValue
        }
    }

    private fun toInteger(raw: Any?): Any? = when (raw) {
        is Int -> raw
        is Long -> raw.toInt()
        is Double -> if (raw == raw.toInt().toDouble()) raw.toInt() else raw
        is Number -> raw.toInt()
        is String -> raw.trim().removeSuffix("℃").removeSuffix("度").toIntOrNull()
        else -> null
    }

    private fun toNumber(raw: Any?): Any? = when (raw) {
        is Int -> raw
        is Long -> raw
        is Double -> raw
        is Number -> raw.toDouble()
        is String -> raw.trim().removeSuffix("℃").removeSuffix("度").toDoubleOrNull()
        else -> null
    }

    /** 参数来源标签（供 argumentSources 可观测性）。 */
    private fun sourceOf(name: String, value: Any?, source: CanonicalizationSource): String = when (source) {
        CanonicalizationSource.L0_RULE -> "USER_EXPLICIT"
        CanonicalizationSource.L1_LOCAL_LLM -> "MODEL_OUTPUT"
        CanonicalizationSource.L3_CLOUD_AI -> "MODEL_OUTPUT"
        CanonicalizationSource.TOOL_VALIDATOR -> "SCHEMA_DEFAULT"
        CanonicalizationSource.TEST_SCORER -> "MODEL_OUTPUT"
    }

    /** 稳定 JSON 序列化：递归排序 key，保证同参数不同顺序产生同一序列化。 */
    private fun stableJsonObject(arguments: Map<String, Any?>): Map<String, Any?> {
        val sorted = LinkedHashMap<String, Any?>()
        arguments.entries.sortedBy { it.key }.forEach { (k, v) ->
            sorted[k] = when (v) {
                is Map<*, *> -> stableJsonObject(v.entries.associate { (a, b) -> a as String to b })
                is List<*> -> v.map { item -> if (item is Map<*, *>) {
                    stableJsonObject(item.entries.associate { (a, b) -> a as String to b })
                } else {
                    item
                } }
                else -> v
            }
        }
        return sorted
    }

    private companion object {
        const val PARAM_CANONICALIZE = "IVAI-PARAM-001"
    }
}
