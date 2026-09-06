package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotPattern
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.AliasSourceType

/**
 * 旧 ID → canonical 映射（IVI-IVAI-DSN-CR-010）。
 *
 * 旧 Tool ID / Function-ID / 表达 保留在 Alias 中，不作为第二套可执行
 * ToolDefinition，也不计入 160 Tool 数量；运行时候选构建前 canonicalize 到
 * 治理 Tool ID。[presetArguments] 为该表达预置的参数（“打开空调”→ enabled=true）。
 */
data class LegacyAliasMapping(
    val aliasId: String,
    val legacyId: String,
    val canonicalToolId: String,
    val presetArguments: Map<String, Any?> = emptyMap(),
    val sourceType: AliasSourceType = AliasSourceType.LEGACY_TOOL_ID
)

/**
 * 单个 canonical Tool 的确定性匹配画像（IVI-IVAI-DSN-CR-010）。
 *
 * [rules] 为请求级 DeterministicMatchProfile；[legacyAliases] 记录旧 ID /
 * Function-ID / 表达映射；[positiveExamples] 供 DomainRouter / L1 Retriever
 * 识别明确表达（治理覆盖建设，P0 先行）。
 */
data class ToolMatchProfile(
    val toolId: String,
    val name: String,
    val rules: List<DeterministicIntentRule> = emptyList(),
    val legacyAliases: List<LegacyAliasMapping> = emptyList(),
    val positiveExamples: List<String> = emptyList()
)

/**
 * 确定性匹配元数据目录（IVI-IVAI-DSN-CR-010）。
 *
 * P0 范围先行为「能够安全直达的显式表达」补齐 canonical Alias 与槽位模板：
 * 4 个 canonical 空调 Tool + seat.massage.set（验收“打开座椅按摩”）。
 * 其余 Tool 无规则 → 明确表达落入 L1 并记录 DETERMINISTIC_COVERAGE_MISSING
 * 治理缺口（IVAI-ROUTE-004），不得解释为“该 Tool 天生只能走 L1”。
 * P0～P3 仅控制建设顺序，不参与运行时判断。
 */
object ToolAliasCatalog {

    private val zoneSlot = SlotPattern(
        name = "zone",
        type = SlotType.POSITION,
        required = false,
        aliases = mapOf(
            "主驾" to "driver", "驾驶位" to "driver", "司机位" to "driver", "主驾驶" to "driver",
            "副驾" to "passenger", "副驾驶" to "passenger", "乘客位" to "passenger",
            "前排" to "front", "后排" to "rear", "全车" to "all"
        )
    )

    private val positionSlot = SlotPattern(
        name = "position",
        type = SlotType.POSITION,
        required = false,
        aliases = mapOf(
            "主驾" to "driver", "驾驶位" to "driver", "司机位" to "driver", "主驾驶" to "driver",
            "副驾" to "passenger", "副驾驶" to "passenger", "乘客位" to "passenger",
            "前排" to "front", "后排" to "rear"
        )
    )

    /** 6 个旧空调 ID 的 canonical 目标（CR-010 迁移表）。 */
    const val CANONICAL_POWER_SET = "climate.power.set"
    const val CANONICAL_TEMPERATURE_SET = "climate.temperature.set"
    const val CANONICAL_TEMPERATURE_ADJUST = "climate.temperature.adjust"
    const val CANONICAL_STATUS_QUERY = "climate.status.query"
    const val CANONICAL_SEAT_MASSAGE_SET = "seat.massage.set"

    val PROFILES: Map<String, ToolMatchProfile> = mapOf(
        CANONICAL_POWER_SET to ToolMatchProfile(
            toolId = CANONICAL_POWER_SET,
            name = "设置空调电源",
            rules = listOf(
                DeterministicIntentRule(
                    ruleId = "L0.power.set.on",
                    toolId = CANONICAL_POWER_SET,
                    exactPhrases = listOf("打开空调", "开启空调", "把空调打开", "开空调", "空调打开"),
                    negativePatterns = listOf("关闭", "关掉", "关上"),
                    slotPatterns = listOf(zoneSlot),
                    presetArguments = mapOf("enabled" to true),
                    priority = 10
                ),
                DeterministicIntentRule(
                    ruleId = "L0.power.set.off",
                    toolId = CANONICAL_POWER_SET,
                    exactPhrases = listOf("关闭空调", "关空调", "把空调关掉", "关掉空调", "空调关闭"),
                    negativePatterns = listOf("打开", "开启"),
                    slotPatterns = listOf(zoneSlot),
                    presetArguments = mapOf("enabled" to false),
                    priority = 10
                )
            ),
            legacyAliases = listOf(
                LegacyAliasMapping("alias.power_on.legacy", "climate.power_on", CANONICAL_POWER_SET, mapOf("enabled" to true)),
                LegacyAliasMapping("alias.power_off.legacy", "climate.power_off", CANONICAL_POWER_SET, mapOf("enabled" to false)),
                LegacyAliasMapping("alias.power.fn.1", "AC_Control_1", CANONICAL_POWER_SET, mapOf("enabled" to true), AliasSourceType.FUNCTION_ID),
                LegacyAliasMapping("alias.power.fn.2", "AC_Control_2", CANONICAL_POWER_SET, mapOf("enabled" to false), AliasSourceType.FUNCTION_ID)
            ),
            positiveExamples = listOf("打开空调", "开启空调", "关闭空调", "关空调")
        ),
        CANONICAL_TEMPERATURE_SET to ToolMatchProfile(
            toolId = CANONICAL_TEMPERATURE_SET,
            name = "设置目标温度",
            rules = listOf(
                DeterministicIntentRule(
                    ruleId = "L0.temperature.set.1",
                    toolId = CANONICAL_TEMPERATURE_SET,
                    exactPhrases = listOf("温度调到", "调到", "设为", "设成", "空调设成", "调成", "温度设为"),
                    slotPatterns = listOf(
                        SlotPattern(name = "temperature", type = SlotType.TEMPERATURE, required = true),
                        zoneSlot
                    ),
                    priority = 10
                )
            ),
            legacyAliases = listOf(
                LegacyAliasMapping("alias.temperature_set.legacy", "climate.temperature_set", CANONICAL_TEMPERATURE_SET),
                LegacyAliasMapping("alias.temperature_set.fn", "AC_Temperature_1", CANONICAL_TEMPERATURE_SET, sourceType = AliasSourceType.FUNCTION_ID)
            ),
            positiveExamples = listOf("温度调到24度", "空调设成25度", "主驾调到24度")
        ),
        CANONICAL_TEMPERATURE_ADJUST to ToolMatchProfile(
            toolId = CANONICAL_TEMPERATURE_ADJUST,
            name = "调节温度",
            rules = listOf(
                DeterministicIntentRule(
                    ruleId = "L0.temperature.adjust.up",
                    toolId = CANONICAL_TEMPERATURE_ADJUST,
                    exactPhrases = listOf("温度调高一点", "温度调高", "调高温度", "调高点", "升温", "温度高一点"),
                    slotPatterns = listOf(zoneSlot, SlotPattern(name = "step", type = SlotType.STEP, required = false)),
                    presetArguments = mapOf("direction" to "increase"),
                    priority = 5
                ),
                DeterministicIntentRule(
                    ruleId = "L0.temperature.adjust.down",
                    toolId = CANONICAL_TEMPERATURE_ADJUST,
                    exactPhrases = listOf("温度调低一点", "温度调低", "调低温度", "调低点", "降温", "温度低一点"),
                    slotPatterns = listOf(zoneSlot, SlotPattern(name = "step", type = SlotType.STEP, required = false)),
                    presetArguments = mapOf("direction" to "decrease"),
                    priority = 5
                ),
                // CR-010：CR-008 表达 Alias（主驾升温/副驾升温/主驾降温/副驾降温）→
                // 同一参数化 canonical Tool，由 Alias 预置 zone + direction 直接走 L0。
                DeterministicIntentRule(
                    ruleId = "L0.temperature.adjust.expression.driver_up",
                    toolId = CANONICAL_TEMPERATURE_ADJUST,
                    exactPhrases = listOf("主驾升温", "驾驶位升温", "主驾驶升温"),
                    presetArguments = mapOf("zone" to "driver", "direction" to "increase"),
                    priority = 8
                ),
                DeterministicIntentRule(
                    ruleId = "L0.temperature.adjust.expression.passenger_up",
                    toolId = CANONICAL_TEMPERATURE_ADJUST,
                    exactPhrases = listOf("副驾升温", "副驾驶升温", "乘客位升温"),
                    presetArguments = mapOf("zone" to "passenger", "direction" to "increase"),
                    priority = 8
                ),
                DeterministicIntentRule(
                    ruleId = "L0.temperature.adjust.expression.driver_down",
                    toolId = CANONICAL_TEMPERATURE_ADJUST,
                    exactPhrases = listOf("主驾降温", "驾驶位降温", "主驾驶降温"),
                    presetArguments = mapOf("zone" to "driver", "direction" to "decrease"),
                    priority = 8
                ),
                DeterministicIntentRule(
                    ruleId = "L0.temperature.adjust.expression.passenger_down",
                    toolId = CANONICAL_TEMPERATURE_ADJUST,
                    exactPhrases = listOf("副驾降温", "副驾驶降温", "乘客位降温"),
                    presetArguments = mapOf("zone" to "passenger", "direction" to "decrease"),
                    priority = 8
                )
            ),
            legacyAliases = listOf(
                LegacyAliasMapping("alias.temperature_increase.legacy", "climate.temperature_increase", CANONICAL_TEMPERATURE_ADJUST, mapOf("direction" to "increase")),
                LegacyAliasMapping("alias.temperature_decrease.legacy", "climate.temperature_decrease", CANONICAL_TEMPERATURE_ADJUST, mapOf("direction" to "decrease")),
                LegacyAliasMapping("alias.temperature_adjust.fn.2", "AC_Temperature_2", CANONICAL_TEMPERATURE_ADJUST, mapOf("direction" to "increase"), AliasSourceType.FUNCTION_ID),
                LegacyAliasMapping("alias.temperature_adjust.fn.3", "AC_Temperature_3", CANONICAL_TEMPERATURE_ADJUST, mapOf("direction" to "decrease"), AliasSourceType.FUNCTION_ID)
            ),
            positiveExamples = listOf("温度调高一点", "温度调低一点", "升温", "降温")
        ),
        CANONICAL_STATUS_QUERY to ToolMatchProfile(
            toolId = CANONICAL_STATUS_QUERY,
            name = "查询空调状态",
            rules = listOf(
                DeterministicIntentRule(
                    ruleId = "L0.status.query.1",
                    toolId = CANONICAL_STATUS_QUERY,
                    exactPhrases = listOf("空调开了吗", "空调关了吗", "现在多少度", "空调状态", "查一下空调", "查看空调状态", "空调现在多少度"),
                    priority = 5
                )
            ),
            legacyAliases = listOf(
                LegacyAliasMapping("alias.status_query.legacy", "climate.status_query", CANONICAL_STATUS_QUERY)
            ),
            positiveExamples = listOf("查看空调状态", "空调开了吗", "现在多少度")
        ),
        CANONICAL_SEAT_MASSAGE_SET to ToolMatchProfile(
            toolId = CANONICAL_SEAT_MASSAGE_SET,
            name = "设置座椅按摩",
            rules = listOf(
                DeterministicIntentRule(
                    ruleId = "L0.seat.massage.on",
                    toolId = CANONICAL_SEAT_MASSAGE_SET,
                    exactPhrases = listOf("打开座椅按摩", "开启座椅按摩", "打开按摩", "座椅按摩打开"),
                    negativePatterns = listOf("关闭", "关掉", "关上"),
                    slotPatterns = listOf(positionSlot),
                    presetArguments = mapOf("enabled" to true),
                    priority = 10
                ),
                DeterministicIntentRule(
                    ruleId = "L0.seat.massage.off",
                    toolId = CANONICAL_SEAT_MASSAGE_SET,
                    exactPhrases = listOf("关闭座椅按摩", "关掉座椅按摩", "关闭按摩"),
                    negativePatterns = listOf("打开", "开启"),
                    slotPatterns = listOf(positionSlot),
                    presetArguments = mapOf("enabled" to false),
                    priority = 10
                )
            ),
            positiveExamples = listOf("打开座椅按摩", "关闭座椅按摩")
        )
    )

    /** 旧 ID / Function-ID → canonical Tool ID（去重后的单映射表）。 */
    fun legacyToCanonical(): Map<String, String> =
        PROFILES.values.flatMap { it.legacyAliases }
            .associate { it.legacyId to it.canonicalToolId }

    /** 取某个 canonical Tool 的确定性画像（无则返回 null → 该 Tool 无 L0 元数据）。 */
    fun profileFor(toolId: String): ToolMatchProfile? = PROFILES[toolId]

    /** 旧 ID → 预置参数（“打开空调”→ enabled=true 等）。 */
    fun presetArgumentsFor(legacyId: String): Map<String, Any?> =
        PROFILES.values.flatMap { it.legacyAliases }
            .firstOrNull { it.legacyId == legacyId }
            ?.presetArguments ?: emptyMap()
}
