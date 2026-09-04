package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateSnapshot

/**
 * Normalized user input produced before any tier routing (CR-005): original
 * text, a normalized form (full-width → half-width, punctuation unified,
 * whitespace stripped) and pre-computed negation / multi-intent flags.
 */
data class NormalizedInput(
    val original: String,
    val normalized: String,
    val hasNegation: Boolean = false,
    val hasMultiIntent: Boolean = false
)

/**
 * Per-request routing context (CR-005). Immutable for the duration of one turn.
 */
data class AgentContext(
    val requestId: String,
    val sessionId: String,
    val source: String,
    val vehicleModel: String? = null,
    val softwareVersion: String? = null,
    val vehicleState: VehicleStateSnapshot? = null
)
