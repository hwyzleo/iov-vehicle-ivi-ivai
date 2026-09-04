package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions

/**
 * A deterministic L0 routing rule binding a tool to exact phrases / synonym
 * patterns / slot patterns (IVI-IVAI-DSN-CR-005). Rules live with the Tool
 * Definition so intent matching is data-driven — not scattered hard-coded if/else.
 *
 * Matching model (implemented by the L0 FastIntentMatcher):
 *  - the normalized input is matched against [exactPhrases] (substring) and
 *    [synonymPatterns] (regex);
 *  - [slotPatterns] are then extracted from the input;
 *  - a unique, complete, low-risk, non-negated, non-conflicting match wins L0.
 */
data class DeterministicIntentRule(
    val ruleId: String,
    val toolId: String,
    val exactPhrases: List<String>,
    val synonymPatterns: List<String> = emptyList(),
    val slotPatterns: List<SlotPattern> = emptyList(),
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

/** Version range constraint for rule applicability (nullable bound = unbounded). */
data class VersionConstraint(
    val min: String? = null,
    val max: String? = null
)
