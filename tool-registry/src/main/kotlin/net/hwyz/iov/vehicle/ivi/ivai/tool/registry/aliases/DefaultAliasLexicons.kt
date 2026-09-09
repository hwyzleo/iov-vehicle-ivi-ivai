package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.L0AliasVocabularies
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ToolAliasCatalog

/**
 * 默认 CanonicalAliasLexicon 工厂（IVI-IVAI-DSN-CR-016）。
 *
 * 装配：
 *  - 位置词表：评审闭合的 [L0AliasVocabularies.vehicle_position_v1]（主驾→driver、
 *    副驾→passenger、前排→front、后排→rear、全车→all）+ 字段级枚举 Alias
 *    （ALL / all / 全部 / 所有 / 整车 → all）；
 *  - Tool ID Alias：CR-010 旧 ID / Function-ID 迁移表（[ToolAliasCatalog]）；
 *  - 版本号：由参与词表版本拼接，供 IVAI-PARAM-002 一致性校验。
 */
object DefaultAliasLexicons {

    const val VERSION = "ivai-aliases-v1"

    /** 字段级枚举 Alias：zone/position 字段的受控映射（与位置词表语义一致）。 */
    private val zoneFieldAliases: Map<String, Map<String, String>> = mapOf(
        "zone" to canonicalPositionAliases(),
        "position" to canonicalPositionAliases()
    )

    /** 版本化 Alias Lexicon（单例，装配方共享同一实例）。 */
    val DEFAULT: CanonicalAliasLexicon by lazy { build() }

    fun build(): CanonicalAliasLexicon = CanonicalAliasLexicon(
        version = VERSION,
        fieldEnumAliases = zoneFieldAliases,
        parameterNameAliases = emptyMap(),
        toolIdAliases = ToolAliasCatalog.legacyToCanonical(),
        positionAliases = canonicalPositionAliases()
    )

    /** 位置词表 + 字段级枚举 Alias 的统一 canonical 映射。 */
    fun canonicalPositionAliases(): Map<String, String> =
        L0AliasVocabularies.vehicle_position_v1 +
            mapOf(
                "ALL" to "all",
                "all" to "all",
                "全部" to "all",
                "所有" to "all",
                "整车" to "all"
            )
}
