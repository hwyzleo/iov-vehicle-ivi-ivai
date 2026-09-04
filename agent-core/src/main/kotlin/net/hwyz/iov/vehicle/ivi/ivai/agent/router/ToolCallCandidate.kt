package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import kotlinx.serialization.Serializable

/**
 * Origin of a tool call candidate (IVI-IVAI-DSN-CR-005). All sources feed the
 * SAME on-device safe execution chain — L0 only skips RAG/LLM, never any
 * execution safety step.
 */
@Serializable
enum class CandidateSource {
    L0_RULE,
    L1_LOCAL_LLM,
    L3_CLOUD_AI
}

/**
 * Unified tool call candidate produced by L0 rules, L1 local LLM or L3 cloud
 * AI, before validation / policy / idempotency / execution.
 */
data class ToolCallCandidate(
    val requestId: String,
    val toolId: String,
    val arguments: Map<String, Any?>,
    val source: CandidateSource,
    val confidence: Double? = null,
    val evidenceIds: List<String> = emptyList()
)
