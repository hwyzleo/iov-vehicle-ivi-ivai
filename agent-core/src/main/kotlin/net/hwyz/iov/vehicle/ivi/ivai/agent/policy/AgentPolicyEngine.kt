package net.hwyz.iov.vehicle.ivi.ivai.agent.policy

import net.hwyz.iov.vehicle.ivi.ivai.agent.error.ErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentOutput
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.Intent
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.jsonArgsToValues
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.PolicyReason
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateSnapshot

sealed class PolicyOutcome {
    data object Authorized : PolicyOutcome()
    data class NeedsConfirmation(val intents: List<Intent>) : PolicyOutcome()
    data class Denied(val errorCode: ErrorCode, val message: String) : PolicyOutcome()
}

/**
 * Agent-level policy: model needConfirmation, tool risk/precondition.
 */
class AgentPolicyEngine(
    private val registry: ToolRegistry,
    private val toolPolicyEngine: ToolPolicyEngine
) {

    fun evaluate(
        intents: List<Intent>,
        output: AgentOutput,
        vehicleState: VehicleStateSnapshot?,
        confirmationGranted: Boolean
    ): PolicyOutcome {
        if (output.needConfirmation && !confirmationGranted) {
            return PolicyOutcome.NeedsConfirmation(intents)
        }
        for (intent in intents) {
            val tool = registry.get(intent.toolId) ?: continue
            val decision = toolPolicyEngine.check(
                tool,
                jsonArgsToValues(intent.arguments),
                vehicleState
            )
            when (decision.reason) {
                PolicyReason.RISK_REQUIRES_CONFIRMATION ->
                    if (!confirmationGranted) return PolicyOutcome.NeedsConfirmation(listOf(intent))
                PolicyReason.PRECONDITION_UNMET ->
                    return PolicyOutcome.Denied(
                        errorCode = ErrorCode.POLICY_DENIED,
                        message = decision.message ?: "前置条件不满足"
                    )
                PolicyReason.OK -> Unit
            }
        }
        return PolicyOutcome.Authorized
    }
}
