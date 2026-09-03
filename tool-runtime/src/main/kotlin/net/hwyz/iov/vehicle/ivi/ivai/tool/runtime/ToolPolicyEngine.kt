package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.RiskLevel
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition

/**
 * Minimal read-only vehicle state used for precondition checks.
 */
data class VehicleStateSnapshot(
    val powerOn: Boolean
)

enum class PolicyReason {
    OK,
    RISK_REQUIRES_CONFIRMATION,
    PRECONDITION_UNMET
}

data class PolicyDecision(
    val authorized: Boolean,
    val reason: PolicyReason,
    val message: String? = null
)

/**
 * Policy gate before a tool may enter EXECUTING.
 */
class ToolPolicyEngine {

    fun check(
        tool: ToolDefinition,
        args: Map<String, Any?>,
        vehicleState: VehicleStateSnapshot?
    ): PolicyDecision {
        if (tool.policy.requiresPowerOn && (vehicleState == null || !vehicleState.powerOn)) {
            return PolicyDecision(
                authorized = false,
                reason = PolicyReason.PRECONDITION_UNMET,
                message = "Tool ${tool.toolId} requires AC power to be on"
            )
        }
        if (tool.policy.requiresConfirmation || tool.policy.riskLevel == RiskLevel.HIGH) {
            return PolicyDecision(
                authorized = false,
                reason = PolicyReason.RISK_REQUIRES_CONFIRMATION,
                message = "Tool ${tool.toolId} requires user confirmation"
            )
        }
        return PolicyDecision(authorized = true, reason = PolicyReason.OK, message = null)
    }
}
