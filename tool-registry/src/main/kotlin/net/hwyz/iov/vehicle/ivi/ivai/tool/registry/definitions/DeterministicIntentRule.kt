package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions

import kotlinx.serialization.Serializable

/**
 * DeterministicMatchProfile（IVI-IVAI-DSN-CR-010）——Tool 的请求级确定性匹配元数据。
 * CR-005 时期名为 DeterministicIntentRule；CR-010 将其推广为所有可执行 Tool 都可
 * 具备的确定性匹配画像（exactPhrases / synonymPatterns / slotPatterns /
 * negativePatterns / priority / applicableVersions），并由通用 FastIntentMatcher
 * 消费，不再为某批 Tool 建立专用分支。
 *
 * Matching model (implemented by the L0 FastIntentMatcher):
 *  - the normalized input is matched against [exactPhrases] (substring) and
 *    [synonymPatterns] (regex);
 *  - [negativePatterns] 命中 → 该规则被否定（如“关闭空调”不得命中“打开空调”规则）;
 *  - [slotPatterns] 从输入抽取槽位，[presetArguments]（Alias 预置参数）提供
 *    无法从文本确定的必填参数（如“打开空调”→ enabled=true）;
 *  - 唯一 canonical Tool + 必填参数完整 + 无否定/多意图/同优先级冲突 + Policy
 *    允许直达 → L0；否则进入 L1 检索与本地模型。
 */
data class DeterministicIntentRule(
    val ruleId: String,
    val toolId: String,
    val exactPhrases: List<String>,
    val synonymPatterns: List<String> = emptyList(),
    val slotPatterns: List<SlotPattern> = emptyList(),
    /** CR-010：命中任一即否定该规则（如“关闭空调”否定 power.set 的开机规则）。 */
    val negativePatterns: List<String> = emptyList(),
    /** CR-010：Alias / 表达预置参数（“打开空调”→ enabled=true），匹配成功时并入候选参数。 */
    val presetArguments: Map<String, Any?> = emptyMap(),
    val priority: Int = 0,
    val minConfidence: Double = 0.9,
    val applicableVersions: VersionConstraint = VersionConstraint()
)

/**
 * A named slot extracted from the input (temperature, position, step...).
 * [type] drives how the raw token is parsed; [aliases] maps user wording to
 * canonical values (主驾 → driver).
 */
data class SlotPattern(
    val name: String,
    val type: SlotType,
    val required: Boolean = false,
    val aliases: Map<String, String> = emptyMap()
)

/** The kind of slot value that [SlotPattern] extracts and canonicalizes. */
enum class SlotType {
    /** A numeric temperature: 24, 24度, 24℃. */
    TEMPERATURE,

    /** An enumerated position: driver | passenger | all (+ aliases 主驾/副驾/全车). */
    POSITION,

    /** A numeric step for increase/decrease: 1档 / 1. */
    STEP,

    /** A generic integer. */
    NUMERIC
}

/** Version range constraint for rule applicability (nullable bound = unbounded).
 * CR-011: 亦作为 L2 KnowledgeChunk 的软件版本适用范围（可序列化）。 */
@Serializable
data class VersionConstraint(
    val min: String? = null,
    val max: String? = null
)
