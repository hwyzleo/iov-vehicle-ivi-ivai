package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilitySnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouteDecision
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.RetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition

/**
 * Result of the tiered intent routing (IVI-IVAI-DSN-CR-005 + CR-008).
 *
 * @param directCandidate set when the tier is L0 and a unique deterministic
 *   tool call was produced (no RAG / no LLM).
 * @param retrievalQuery set for L1 / L2 — the retriever query to run before
 *   the local model call.
 * @param domain set when CR-008 domain pre-routing is enabled — the business
 *   domain / operation type decision consumed by this route.
 * @param capabilitySnapshot set when CR-008 capability pack selection ran —
 *   the immutable pack-scoped candidate space (L0/L1 must stay inside it).
 * @param workflow set when a registered workflow was deterministically selected
 *   (tier = WORKFLOW_EXECUTION).
 */
data class TieredRouteDecision(
    val tier: IntentTier,
    val confidence: Double,
    val reasonCode: String,
    val directCandidate: ToolCallCandidate? = null,
    val retrievalQuery: RetrievalQuery? = null,
    /** Set when L0 matched a single tool whose required slots are missing. */
    val missingToolId: String? = null,
    val missingArguments: List<String> = emptyList(),
    // CR-008:
    val domain: DomainRouteDecision? = null,
    val capabilitySnapshot: CapabilitySnapshot? = null,
    val workflow: WorkflowDefinition? = null
)

/** Common reason codes produced by the tiered router (CR-005 + CR-008). */
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
    // CR-008:
    const val WORKFLOW_MATCHED = "WORKFLOW_MATCHED"
    const val DOMAIN_NO_AVAILABLE_PACK = "DOMAIN_NO_AVAILABLE_PACK"
    const val DOMAIN_AMBIGUOUS_FALLBACK = "DOMAIN_AMBIGUOUS_FALLBACK"
    const val DOMAIN_LOW_CONFIDENCE_FALLBACK = "DOMAIN_LOW_CONFIDENCE_FALLBACK"
}
