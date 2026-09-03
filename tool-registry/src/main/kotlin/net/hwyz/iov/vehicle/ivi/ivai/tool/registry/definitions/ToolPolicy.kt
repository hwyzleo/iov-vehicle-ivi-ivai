package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions

/**
 * Static risk level of a tool, used by the policy engine before execution.
 */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Tool-level policy: risk, confirmation and precondition requirements.
 */
data class ToolPolicy(
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val requiresConfirmation: Boolean = false,
    val requiresPowerOn: Boolean = false
)
