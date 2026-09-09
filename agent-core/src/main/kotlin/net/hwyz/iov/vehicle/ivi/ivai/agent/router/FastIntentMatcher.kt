package net.hwyz.iov.vehicle.ivi.ivai.agent.router

/**
 * Reference to a candidate tool with its matched rule + confidence, used for
 * ambiguity reporting (CR-005).
 */
data class ToolCandidateRef(
    val toolId: String,
    val ruleId: String?,
    val confidence: Double
)

/**
 * L0 deterministic fast intent matching (CR-005 + CR-013). Never calls RAG or
 * the LLM; only returns a [FastIntentMatchResult.Unique] when the input
 * uniquely maps to one tool with complete required slots, low risk, no
 * negation / multi-intent / intent-type conflict / argument contradiction and
 * matching vehicle / software version.
 */
interface FastIntentMatcher {
    suspend fun match(input: NormalizedInput, context: AgentContext): FastIntentMatchResult
}

sealed interface FastIntentMatchResult {

    /** Unique deterministic match — safe to execute without RAG / LLM. */
    data class Unique(
        val candidate: ToolCallCandidate,
        val confidence: Double,
        val reasonCode: String,
        /** CR-010：命中的 DeterministicMatchProfile 规则 ID（observability）。 */
        val matchedPatternId: String? = null,
        /** CR-010：命中/解析出的 canonical Tool ID。 */
        val canonicalToolId: String? = null,
        /** CR-010：本次请求实际命中规则的 Tool 集合（仅 L0 唯一集）。 */
        val matchedToolIds: Set<String> = emptySet(),
        /** CR-013：本次全部命中规则 ID（observability matchedRuleIds）。 */
        val matchedRuleIds: List<String> = emptyList(),
        /** CR-013：候选参数各来源（explicit_slot / rule_preset / ...）。 */
        val argumentSources: Map<String, String> = emptyMap()
    ) : FastIntentMatchResult

    /** Multiple distinct tools matched — conflict, route to L1 / ask (IVAI-ROUTE-003). */
    data class Ambiguous(
        val candidates: List<ToolCandidateRef>,
        /** CR-010：参与冲突的多个 Tool（observability，IVAI-ROUTE-003）。 */
        val matchedToolIds: Set<String> = emptySet(),
        /** CR-013：参与全局冲突判定的全部规则 ID。 */
        val matchedRuleIds: List<String> = emptyList(),
        /** CR-013：全部已命中的 deterministic 候选数。 */
        val deterministicCandidateCount: Int = 0
    ) : FastIntentMatchResult

    /**
     * A single tool matched but required slots are missing.
     *
     * CR-016：[deterministicFallbackReason] 记录类型化降级原因（必填缺失 →
     * REQUIRED_SLOT_MISSING；数值/位置槽位无法解析 → SLOT_UNPARSEABLE；越界 →
     * ARGUMENT_OUT_OF_RANGE），供可观测性展示。
     */
    data class MissingArguments(
        val toolId: String,
        val missing: List<String>,
        val deterministicFallbackReason: String? = null,
        val matchedRuleIds: List<String> = emptyList()
    ) : FastIntentMatchResult

    /**
     * CR-013：单个 Tool 命中但显式槽位与预置/Alias 参数存在不可安全消解的矛盾
     * （IVAI-ROUTE-005）。不得以优先级静默覆盖用户明确语义。
     */
    data class ArgumentConflict(
        val toolId: String,
        val conflictingArgument: String,
        /** argument → 冲突来源（explicit_slot / rule_preset / ...）。 */
        val sources: Map<String, String> = emptyMap(),
        val deterministicFallbackReason: String? = null,
        val matchedRuleIds: List<String> = emptyList()
    ) : FastIntentMatchResult

    /**
     * CR-019：温度语义已确定为不可执行的业务终态（如绝对温度越界
     * IVAI-TEMP-RANGE-001）→ 直接拒绝，不进入 L1 执行。
     */
    data class Rejected(
        val reasonCode: String,
        /** 命中的确定性规则 ID（可观测性）。 */
        val matchedRuleIds: List<String> = emptyList(),
        /** 语义证据（温度操作/数值/边界）。 */
        val semantic: String? = null
    ) : FastIntentMatchResult

    /**
     * CR-019：温度语义无法判定（缺少单位 / 动作对象无法判断，
     * IVAI-TEMP-SEMANTIC-001）→ 需要追问，不产生可执行候选。
     */
    data class NeedsDialogue(
        val reasonCode: String,
        /** 缺失/待澄清的参数（可观测性与追问提示）。 */
        val missing: List<String> = emptyList(),
        val matchedRuleIds: List<String> = emptyList(),
        val semantic: String? = null
    ) : FastIntentMatchResult

    /** No deterministic match — continue to L1 / L2 / L3. */
    data object NoMatch : FastIntentMatchResult
}
