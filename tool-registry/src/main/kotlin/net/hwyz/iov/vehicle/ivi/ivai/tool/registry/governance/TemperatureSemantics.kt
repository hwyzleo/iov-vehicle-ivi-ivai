package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionRange

/**
 * 温度操作语义（IVI-IVAI-DSN-CR-019）。
 *
 * 由 [TemperatureIntentSemanticNormalizer]（agent-core）从归一化输入判定，是
 * 温度 Tool 边界、L0 匹配、Tool RAG 排序与评分契约的单一语义事实源。
 * 判定规则见 CR-019 设计「温度语义标准化」。
 */
enum class TemperatureOperationSemantic {
    /** 相对增减：提高/降低/升高/减少/加/减 + N度 → climate.temperature.adjust。 */
    RELATIVE_DELTA,

    /** 绝对目标：调到/设为/目标/保持在 + 16～30℃ → climate.temperature.set。 */
    ABSOLUTE_TARGET,

    /** 边界目标：最高/最大/最热/最低/最小/最冷 → 版本化车型边界转换。 */
    BOUND_TARGET,

    /** 绝对目标超出车型温度范围 → 拒绝执行。 */
    OUT_OF_RANGE,

    /** 数值缺少单位或动作对象无法判断 → 追问。 */
    AMBIGUOUS
}

/** 温度边界枚举（temperature_bound_v1 词典 canonical 值）。 */
enum class TemperatureBound {
    MAXIMUM,
    MINIMUM
}

/**
 * 温度语义证据（IVI-IVAI-DSN-CR-019 数据契约）。
 *
 * [operation] 判定结果；[zone]/[direction]/[step]/[temperature]/[bound] 为
 * 判定过程中收敛的候选槽位（未必全部非空）；[evidence] 为命中的表达证据；
 * [lexiconVersions] 记录参与的词典版本（delta / bound / position / schema），
 * 供请求快照写入与一致性校验。
 */
data class TemperatureSemanticEvidence(
    val operation: TemperatureOperationSemantic,
    val zone: String? = null,
    val direction: String? = null,
    val step: Double? = null,
    val temperature: Double? = null,
    val bound: TemperatureBound? = null,
    val evidence: List<String> = emptyList(),
    val lexiconVersions: Set<String> = emptySet()
)

/**
 * 定性幅度词典条目（IVI-IVAI-DSN-CR-019 数据契约）。
 *
 * [canonicalStep] 为固定映射的步进（一丢丢=0.5 等）；当车型不允许固定映射时
 * [qualitativeValue] 输出 SMALL/MEDIUM 等受控值并由 Binding 转换，禁止模型
 * 自由猜测（IVAI-TEMP-DELTA-001 保护）。[vehicleScope] 为版本约束 spec
 * （"*" 表示全车型，走 [VersionRange.matches]）。
 */
data class TemperatureDeltaAlias(
    val phrase: String,
    val canonicalStep: Double?,
    val qualitativeValue: String?,
    val vehicleScope: String?,
    val governanceVersion: String
)

/**
 * 定性幅度词典（IVI-IVAI-DSN-CR-019 首期开发验证建议，单一事实源）。
 *
 * 首期固定映射：一丢丢=0.5、半度=0.5、一点=1、一些=1、明显一些=2。
 * 车型不允许固定映射时应由 Binding 按受控 qualitativeValue 转换。
 */
object TemperatureDeltaAliasCatalog {

    const val GOVERNANCE_VERSION = "ivai-temperature-delta-v1"

    val ALL: List<TemperatureDeltaAlias> = listOf(
        alias("一丢丢", 0.5, "SMALL"),
        alias("半度", 0.5, "SMALL"),
        alias("一点", 1.0, "MEDIUM"),
        alias("一些", 1.0, "MEDIUM"),
        alias("明显一些", 2.0, "LARGE")
    )

    /** 按短语查词典（最长优先；返回 null 表示未命中 → IVAI-TEMP-DELTA-001）。 */
    fun resolve(phrase: String): TemperatureDeltaAlias? = ALL.firstOrNull { it.phrase == phrase }

    /** 从归一化文本中提取命中的定性幅度短语（最长优先）。 */
    fun findIn(text: String): TemperatureDeltaAlias? =
        ALL.sortedByDescending { it.phrase.length }
            .firstOrNull { text.contains(it.phrase) }

    private fun alias(phrase: String, step: Double, qualitative: String): TemperatureDeltaAlias =
        TemperatureDeltaAlias(
            phrase = phrase,
            canonicalStep = step,
            qualitativeValue = qualitative,
            vehicleScope = "*",
            governanceVersion = GOVERNANCE_VERSION
        )
}
