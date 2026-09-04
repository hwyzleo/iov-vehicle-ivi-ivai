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
        val reasonCode: String
    ) : FastIntentMatchResult

    /** Multiple distinct tools matched — conflict, route to L1 / ask. */
    data class Ambiguous(val candidates: List<ToolCandidateRef>) : FastIntentMatchResult

    /** A single tool matched but required slots are missing. */
    data class MissingArguments(val toolId: String, val missing: List<String>) : FastIntentMatchResult

    /** No deterministic match — continue to L1 / L2 / L3. */
    data object NoMatch : FastIntentMatchResult
}
