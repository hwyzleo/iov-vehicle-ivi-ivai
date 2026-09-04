package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.RetrievalQuery

/**
 * Result of the tiered intent routing (IVI-IVAI-DSN-CR-005).
 *
 * @param directCandidate set when the tier is L0 and a unique deterministic
 *   tool call was produced (no RAG / no LLM).
 * @param retrievalQuery set for L1 / L2 — the retriever query to run before
 *   the local model call.
 */
data class TieredRouteDecision(
    val tier: IntentTier,
    val confidence: Double,
    val reasonCode: String,
    val directCandidate: ToolCallCandidate? = null,
    val retrievalQuery: RetrievalQuery? = null,
    /** Set when L0 matched a single tool whose required slots are missing. */
    val missingToolId: String? = null,
    val missingArguments: List<String> = emptyList()
)

/** Common reason codes produced by the tiered router (CR-005). */
object RouteReasonCode {
    const val L0_UNIQUE_MATCH = "L0_UNIQUE_MATCH"
    const val L0_RULE_AMBIGUOUS = "L0_RULE_AMBIGUOUS"
    const val L0_MISSING_ARGUMENTS = "L0_MISSING_ARGUMENTS"
    const val L0_NEGATED = "L0_NEGATED"
    const val L0_MULTI_INTENT = "L0_MULTI_INTENT"
    const val L0_NO_MATCH = "L0_NO_MATCH"
    const val L1_TOOL_DOMAIN = "L1_TOOL_DOMAIN"
    const val L1_TOOL_FALLBACK_CANDIDATES = "L1_TOOL_FALLBACK_CANDIDATES"
    const val L2_KNOWLEDGE_DOMAIN = "L2_KNOWLEDGE_DOMAIN"
    const val L2_NO_KNOWLEDGE = "L2_KNOWLEDGE_UNAVAILABLE"
    const val RAG_RETRIEVAL_EMPTY = "RAG_RETRIEVAL_EMPTY"
    const val L3_OPEN_DOMAIN = "L3_OPEN_DOMAIN"
    const val REJECT_SAFETY = "REJECT_SAFETY"
    const val REJECT_UNSUPPORTED = "REJECT_UNSUPPORTED"
}
