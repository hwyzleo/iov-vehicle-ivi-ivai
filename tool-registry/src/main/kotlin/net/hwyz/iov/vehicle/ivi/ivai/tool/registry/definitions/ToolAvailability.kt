package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions

/**
 * Deployment / version availability of a tool (IVI-IVAI-DSN-CR-005). Used by
 * L0 pre-filtering and by the unified execution chain before any candidate —
 * regardless of source — is run.
 */
data class ToolAvailability(
    val enabled: Boolean = true,
    val vehicleModels: List<String> = listOf("*"),
    val softwareRange: String? = null, // e.g. ">=0.1.0"
    val language: String = "zh-CN"
)
