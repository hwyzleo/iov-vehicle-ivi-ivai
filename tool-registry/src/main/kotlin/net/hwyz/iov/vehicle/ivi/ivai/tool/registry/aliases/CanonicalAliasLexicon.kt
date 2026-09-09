package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases

/**
 * 版本化 Alias 词表（IVI-IVAI-DSN-CR-016）。
 *
 * 集中维护字段级枚举 Alias、参数名 Alias 与 Tool ID Alias，供
 * [net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema.ParameterCanonicalizationService]
 * 与 L0/L1/评分共用，防止 L0/L1/执行/评分语义漂移。
 *
 * 约束（REQ-160 / 设计结论）：
 *  - 枚举只能依据当前 Tool Schema 与字段级 Alias 表转换；
 *  - 可将 ALL / all / 全部 / 所有 / 整车 等映射为 Schema 批准的同一 canonical 值，
 *    但不得对自由文本统一 lowercase；
 *  - Alias/Schema/Canonicalizer 版本不一致时输出 IVAI-PARAM-002。
 */
data class CanonicalAliasLexicon(
    /** 词表版本（参与一致性校验 IVAI-PARAM-002）。 */
    val version: String,
    /** 字段级枚举 Alias：参数名 → (输入值 → canonical 值)。 */
    val fieldEnumAliases: Map<String, Map<String, String>> = emptyMap(),
    /** 参数名 Alias：用户/模型使用的参数名 → Schema canonical 参数名。 */
    val parameterNameAliases: Map<String, String> = emptyMap(),
    /** Tool ID / Function ID Alias → canonical Tool ID（旧 ID 迁移，CR-010）。 */
    val toolIdAliases: Map<String, String> = emptyMap(),
    /** 位置/区域词 → canonical（受控词表，来自 L0AliasVocabularies 评审闭合部分）。 */
    val positionAliases: Map<String, String> = emptyMap(),
    /**
     * 定性业务词典（CR-017，fan_level_qualitative_v1）：参数名 → 短语 → 固定值。
     * 如 level: 中等→5；禁止模型自由猜测档位（REQ-182）。
     */
    val fieldQualitativeAliases: Map<String, Map<String, Any?>> = emptyMap()
) {
    /** 查询字段级枚举 canonical；无映射时返回原值。 */
    fun enumCanonical(parameterName: String, value: String): String =
        fieldEnumAliases[parameterName]?.get(value) ?: value

    /** 该参数名是否存在字段级枚举 Alias 表。 */
    fun hasFieldEnumAliases(parameterName: String): Boolean =
        fieldEnumAliases.containsKey(parameterName)

    /** canonical 参数名；无映射时返回原名。 */
    fun canonicalParameterName(name: String): String =
        parameterNameAliases[name] ?: name

    /** canonical Tool ID；无映射时返回原名。 */
    fun canonicalToolId(id: String): String =
        toolIdAliases[id] ?: id
}
