package net.hwyz.iov.vehicle.ivi.ivai.agent.event

import kotlinx.serialization.Serializable
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSource
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier

/**
 * Structured execution path returned in the FINAL agent events (CR-005). The
 * Chatbot renders the tier badge from this contract — it never infers the tier
 * from text or call behavior.
 *
 * Examples: L0 direct execution (initial=L0, final=L0); L0 conflict resolved by
 * local model (L0→L1, final=L1); L1 escalated to cloud (L1→L3, final=L3);
 * knowledge no-evidence escalation (L2→L3, final=L3).
 */
@Serializable
data class AgentExecutionPath(
    val initialTier: IntentTier,
    val finalTier: IntentTier,
    val transitions: List<TierTransition> = emptyList(),
    val finalReasonCode: String,
    val candidateSource: CandidateSource? = null
)

@Serializable
data class TierTransition(
    val from: IntentTier,
    val to: IntentTier,
    val reasonCode: String
)
