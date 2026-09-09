package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.L0AliasVocabularies
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ToolAliasCatalog

/**
 * 默认 CanonicalAliasLexicon 工厂（IVI-IVAI-DSN-CR-016 + CR-017）。
 *
 * 装配：
 *  - 位置词表：评审闭合的 [L0AliasVocabularies.vehicle_position_v2]（9 canonical zone，
 *    含 中左/中右/2排/3排 等 CR-017 批准 Alias）+ 模型输出侧字段级枚举 Alias
 *    （ALL/ALL_ZONES/whole_vehicle→all、ROW2/ZONE2→second_row、3RD/ROW3→third_row）；
 *  - Tool ID Alias：CR-010 旧 ID / Function-ID 迁移表（[ToolAliasCatalog]）；
 *  - 版本号：由参与词表版本拼接，供 IVAI-PARAM-002 一致性校验。
 */
object DefaultAliasLexicons {

    const val VERSION = "ivai-aliases-v2"

    /** 字段级枚举 Alias：zone/position 字段的受控映射（与位置词表语义一致）。 */
    private val zoneFieldAliases: Map<String, Map<String, String>> = mapOf(
        "zone" to canonicalPositionAliases(),
        "position" to canonicalPositionAliases()
    )

    /** 版本化 Alias Lexicon（单例，装配方共享同一实例）。 */
    val DEFAULT: CanonicalAliasLexicon by lazy { build() }

    /** 定性风量档位词典（CR-017，fan_level_qualitative_v1）：业务固定映射，禁止模型猜测。 */
    val fanLevelQualitativeV1: Map<String, Int> = mapOf(
        "中等" to 5
    )

    fun build(): CanonicalAliasLexicon = CanonicalAliasLexicon(
        version = VERSION,
        fieldEnumAliases = zoneFieldAliases,
        parameterNameAliases = emptyMap(),
        toolIdAliases = ToolAliasCatalog.legacyToCanonical(),
        positionAliases = canonicalPositionAliases(),
        fieldQualitativeAliases = mapOf(
            "level" to fanLevelQualitativeV1
        )
    )

    /** 位置词表 + 字段级枚举 Alias 的统一 canonical 映射（CR-017 同源 vehicle_position_v2）。 */
    fun canonicalPositionAliases(): Map<String, String> =
        L0AliasVocabularies.vehicle_position_v2 +
            mapOf(
                "ALL" to "all",
                "all" to "all",
                "ALL_ZONES" to "all",
                "whole_vehicle" to "all",
                "ROW2" to "second_row",
                "ZONE2" to "second_row",
                "ROW3" to "third_row",
                "3RD" to "third_row"
            )
}
