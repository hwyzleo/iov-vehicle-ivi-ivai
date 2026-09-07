package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.AliasSourceType

/**
 * 旧 ID → canonical 映射（IVI-IVAI-DSN-CR-010 迁移表，保留）。
 *
 * 旧 Tool ID / Function-ID / 表达保留在 Alias 中，不作为第二套可执行
 * ToolDefinition，也不计入 160 Tool 数量；运行时候选构建前 canonicalize 到
 * 治理 Tool ID。[presetArguments] 为该表达预置的参数（“打开空调”→ enabled=true）。
 *
 * CR-013：LegacyAliasMapping 只是 ID 迁移数据，不是 L0 规则事实源；L0 规则
 * 一律来自 IVAI Tool Catalog v1（[DeterministicIntentCatalog]）。
 */
data class LegacyAliasMapping(
    val aliasId: String,
    val legacyId: String,
    val canonicalToolId: String,
    val presetArguments: Map<String, Any?> = emptyMap(),
    val sourceType: AliasSourceType = AliasSourceType.LEGACY_TOOL_ID
)

/**
 * 单个 canonical Tool 的确定性匹配画像（IVI-IVAI-DSN-CR-010 + CR-013）。
 *
 * [rules] 为编译后的 L0 规则（由 IVAI Tool Catalog v1 生成，不再手写）；
 * [legacyAliases] 记录旧 ID / Function-ID 迁移映射；[positiveExamples] 供
 * DomainRouter / L1 Retriever 识别明确表达。
 */
data class ToolMatchProfile(
    val toolId: String,
    val name: String,
    val rules: List<DeterministicIntentRule> = emptyList(),
    val legacyAliases: List<LegacyAliasMapping> = emptyList(),
    val positiveExamples: List<String> = emptyList()
)

/**
 * 确定性匹配元数据目录（IVI-IVAI-DSN-CR-010 + CR-013）。
 *
 * CR-013：Catalog（IVAI Tool Catalog v1 的 L0 治理字段）是规则事实源，
 * [PROFILES] 是构建期生成的运行时视图，不再长期维护两套互相漂移的规则。
 * [legacyAliases] 为 CR-010 旧空调 P0 集迁移数据，保留用于 canonical 收敛。
 */
object ToolAliasCatalog {

    /** 6 个旧空调 ID 的 canonical 目标（CR-010 迁移表）。 */
    const val CANONICAL_POWER_SET = "climate.power.set"
    const val CANONICAL_TEMPERATURE_SET = "climate.temperature.set"
    const val CANONICAL_TEMPERATURE_ADJUST = "climate.temperature.adjust"
    const val CANONICAL_STATUS_QUERY = "climate.status.query"
    const val CANONICAL_SEAT_MASSAGE_SET = "seat.massage.set"

    /**
     * CR-010 旧 ID / Function-ID 迁移表（canonical → LegacyAliasMapping 列表）。
     * 仅承载 ID 迁移，不含 L0 规则。
     */
    val LEGACY_ALIASES: Map<String, List<LegacyAliasMapping>> = mapOf(
        CANONICAL_POWER_SET to listOf(
            LegacyAliasMapping("alias.power_on.legacy", "climate.power_on", CANONICAL_POWER_SET, mapOf("enabled" to true)),
            LegacyAliasMapping("alias.power_off.legacy", "climate.power_off", CANONICAL_POWER_SET, mapOf("enabled" to false)),
            LegacyAliasMapping("alias.power.fn.1", "AC_Control_1", CANONICAL_POWER_SET, mapOf("enabled" to true), AliasSourceType.FUNCTION_ID),
            LegacyAliasMapping("alias.power.fn.2", "AC_Control_2", CANONICAL_POWER_SET, mapOf("enabled" to false), AliasSourceType.FUNCTION_ID)
        ),
        CANONICAL_TEMPERATURE_SET to listOf(
            LegacyAliasMapping("alias.temperature_set.legacy", "climate.temperature_set", CANONICAL_TEMPERATURE_SET),
            LegacyAliasMapping("alias.temperature_set.fn", "AC_Temperature_1", CANONICAL_TEMPERATURE_SET, sourceType = AliasSourceType.FUNCTION_ID)
        ),
        CANONICAL_TEMPERATURE_ADJUST to listOf(
            LegacyAliasMapping("alias.temperature_increase.legacy", "climate.temperature_increase", CANONICAL_TEMPERATURE_ADJUST, mapOf("direction" to "increase")),
            LegacyAliasMapping("alias.temperature_decrease.legacy", "climate.temperature_decrease", CANONICAL_TEMPERATURE_ADJUST, mapOf("direction" to "decrease")),
            LegacyAliasMapping("alias.temperature_adjust.fn.2", "AC_Temperature_2", CANONICAL_TEMPERATURE_ADJUST, mapOf("direction" to "increase"), AliasSourceType.FUNCTION_ID),
            LegacyAliasMapping("alias.temperature_adjust.fn.3", "AC_Temperature_3", CANONICAL_TEMPERATURE_ADJUST, mapOf("direction" to "decrease"), AliasSourceType.FUNCTION_ID)
        ),
        CANONICAL_STATUS_QUERY to listOf(
            LegacyAliasMapping("alias.status_query.legacy", "climate.status_query", CANONICAL_STATUS_QUERY)
        )
    )

    /** L0 规则 / 正例由 DeterministicIntentCatalog（IVAI Tool Catalog v1）生成。 */
    val PROFILES: Map<String, ToolMatchProfile> by lazy {
        val catalog = DeterministicIntentCatalog.build()
        val names = ToolCatalogV1.ALL.associate { it.toolId to it.name }
        catalog.profiles.values.map { profile ->
            ToolMatchProfile(
                toolId = profile.toolId,
                name = names[profile.toolId] ?: profile.toolId,
                rules = profile.rules,
                legacyAliases = LEGACY_ALIASES[profile.toolId] ?: emptyList(),
                positiveExamples = profile.positiveExamples
            )
        }.associateBy { it.toolId }
    }

    /** 旧 ID / Function-ID → canonical Tool ID（去重后的单映射表）。 */
    fun legacyToCanonical(): Map<String, String> =
        LEGACY_ALIASES.values.flatten()
            .associate { it.legacyId to it.canonicalToolId }

    /** 取某个 canonical Tool 的确定性画像（无则返回 null → 该 Tool 无 L0 元数据）。 */
    fun profileFor(toolId: String): ToolMatchProfile? = PROFILES[toolId]

    /** 旧 ID → 预置参数（“打开空调”→ enabled=true 等）。 */
    fun presetArgumentsFor(legacyId: String): Map<String, Any?> =
        LEGACY_ALIASES.values.flatten()
            .firstOrNull { it.legacyId == legacyId }
            ?.presetArguments ?: emptyMap()
}
