package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

/**
 * 治理目录紧凑参数记法 → JSON Schema 展开器（CR-009 / CR-013 延续）。
 *
 * [ToolCatalogV1] 的 parameterSchema 使用紧凑文本描述参数（示例）：
 *   "{level:enum}"                                → 泛型枚举，取值由 L0 目录批准值补充
 *   "{zone?:enum, temperature:number[16..30], unit:C}"
 *   "{mode:face|feet|defrost|mixed}"              → 字面枚举
 *   "{type:wifi|bluetooth|hotspot, enabled:boolean}"
 *   "{startTime, endTime?, repeat?}"              → 无类型参数默认 string
 *   "{target:open|close|vent, percentage?:0..100}"→ 裸范围按 number 处理
 *   "{}"
 *
 * 展开为标准 JSON Schema 文本，供运行时 [net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition]
 * （L1 Prompt 候选工具描述、Tool RAG 检索文档、Schema 校验）使用。
 *
 * 泛型 `enum`（未给出字面取值）由调用方传入批准的枚举值（来自 L0 治理目录
 * presetArguments，见 [GovernanceWorkspace]）；解析器不得自行臆造取值。
 */
object GovernanceSchemaParser {

    /** 展开后的空 schema（与既有运行时定义一致）。 */
    const val EMPTY_SCHEMA = """{"type":"object","properties":{},"required":[]}"""

    private val RANGE_WITH_TYPE = Regex("""^(int|number)\[(-?[0-9]+(?:\.[0-9]+)?)\.\.(-?[0-9]+(?:\.[0-9]+)?)\]$""")
    private val RANGE_BARE = Regex("""^(-?[0-9]+(?:\.[0-9]+)?)\.\.(-?[0-9]+(?:\.[0-9]+)?)$""")

    private val KNOWN_TYPES = setOf("boolean", "int", "integer", "number", "string", "enum", "array", "object", "any")

    /**
     * 固定常量参数（如单位 `C` / `kmh`）：目录将其声明为无问号的未知 token，
     * 语义是「该参数恒为固定值」而非「模型必须提供的输入」，因此不参与 required。
     */
    internal fun isFixedConstant(typePart: String): Boolean =
        typePart.isNotBlank() &&
            typePart !in KNOWN_TYPES &&
            !RANGE_WITH_TYPE.matches(typePart) &&
            !RANGE_BARE.matches(typePart) &&
            '|' !in typePart

    /**
     * 解析紧凑记法为 JSON Schema 文本（属性按声明顺序、required 按声明顺序，输出确定）。
     *
     * @param enumValues 参数名 → 已批准的枚举值（JSON 字面量，如 `"HIGH"` / `true`）；
     *   仅对声明为 `enum` 且未带字面枚举的参数生效。
     */
    fun parse(compact: String, enumValues: Map<String, List<String>> = emptyMap()): String {
        val body = compact.trim().removePrefix("{").removeSuffix("}").trim()
        if (body.isEmpty()) return EMPTY_SCHEMA

        val properties = mutableListOf<String>()
        val required = mutableListOf<String>()

        body.split(',').forEach { raw ->
            val param = raw.trim()
            if (param.isEmpty()) return@forEach
            val colon = param.indexOf(':')
            val namePart = (if (colon >= 0) param.substring(0, colon) else param).trim()
            val typePart = if (colon >= 0) param.substring(colon + 1).trim() else ""
            val optional = namePart.endsWith("?")
            val name = namePart.removeSuffix("?").trim()
            require(name.isNotEmpty()) { "非法参数名: '$param'（来源 '$compact'）" }
            properties += "\"$name\": ${propertySchema(typePart, enumValues[name].orEmpty())}"
            if (!optional && !isFixedConstant(typePart)) required += "\"$name\""
        }

        return buildString {
            append("""{"type":"object","properties":{""")
            append(properties.joinToString(","))
            append('}')
            if (required.isNotEmpty()) append(",\"required\":[").append(required.joinToString(",")).append(']')
            append('}')
        }
    }

    private fun propertySchema(typePart: String, enumValues: List<String>): String {
        if (typePart.isEmpty()) return """{"type":"string"}"""
        RANGE_WITH_TYPE.matchEntire(typePart)?.let { m ->
            val type = if (m.groupValues[1] == "int") "integer" else "number"
            return """{"type":"$type","minimum":${m.groupValues[2]},"maximum":${m.groupValues[3]}}"""
        }
        RANGE_BARE.matchEntire(typePart)?.let { m ->
            return """{"type":"number","minimum":${m.groupValues[1]},"maximum":${m.groupValues[2]}}"""
        }
        if ('|' in typePart) {
            // CR-017: 字面枚举支持 "=default" 后缀（如 zone?:...|third_row=all → default=all）。
            val (enumPart, default) = splitEnumDefault(typePart)
            val values = enumPart.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            val enumBody = values.joinToString(",") { "\"$it\"" }
            val defaultJson = if (default != null) ",\"default\":\"$default\"" else ""
            return "{\"type\":\"string\",\"enum\":[$enumBody]$defaultJson}"
        }
        return when (typePart) {
            "boolean" -> """{"type":"boolean"}"""
            "int", "integer" -> """{"type":"integer"}"""
            "number" -> """{"type":"number"}"""
            "string" -> """{"type":"string"}"""
            "array" -> """{"type":"array"}"""
            "object" -> """{"type":"object"}"""
            "any" -> """{}"""
            "enum" -> if (enumValues.isNotEmpty())
                """{"type":"string","enum":[${enumValues.joinToString(",")}]}"""
            else
                """{"type":"string"}"""
            // 未知名 token（如单位 C / kmh）视为固定常量：携带 enum + default，不参与必填。
            else -> """{"type":"string","enum":["$typePart"],"default":"$typePart"}"""
        }
    }

    /**
     * 拆分字面枚举与 "=default" 后缀（CR-017）。只有含 '|' 的枚举允许默认值；
     * 默认值必须是枚举成员之一（否则不输出 default）。
     */
    private fun splitEnumDefault(typePart: String): Pair<String, String?> {
        val eq = typePart.lastIndexOf('=')
        if (eq <= 0 || eq == typePart.length - 1) return typePart to null
        val base = typePart.substring(0, eq)
        val def = typePart.substring(eq + 1).trim()
        if ('|' !in base || def.isBlank()) return typePart to null
        val values = base.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        return if (def in values) base to def else typePart to null
    }
}
