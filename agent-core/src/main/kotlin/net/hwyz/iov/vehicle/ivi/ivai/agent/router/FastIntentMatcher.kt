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
 * L0 deterministic fast intent matching (CR-005). Never calls RAG or the LLM;
 * only returns a [FastIntentMatchResult.Unique] when the input uniquely maps to
 * one tool with complete required slots, low risk, no negation / conflict and
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
        val matchedToolIds: Set<String> = emptySet()
    ) : FastIntentMatchResult

    /** Multiple distinct tools matched — conflict, route to L1 / ask. */
    data class Ambiguous(
        val candidates: List<ToolCandidateRef>,
        /** CR-010：参与冲突的多个 Tool（observability，IVAI-ROUTE-003）。 */
        val matchedToolIds: Set<String> = emptySet()
    ) : FastIntentMatchResult

    /** A single tool matched but required slots are missing. */
    data class MissingArguments(val toolId: String, val missing: List<String>) : FastIntentMatchResult

    /** No deterministic match — continue to L1 / L2 / L3. */
    data object NoMatch : FastIntentMatchResult
}
