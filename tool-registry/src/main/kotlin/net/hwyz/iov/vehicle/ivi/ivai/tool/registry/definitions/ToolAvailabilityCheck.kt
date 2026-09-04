package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions

/**
 * Tool availability pre-filter (CR-005): enabled state, vehicle model and
 * software version. Applied by L0 matching, by all tool candidate providers and
 * by retrievers so that no disabled / incompatible tool is ever recalled,
 * selected or executed.
 */
object ToolAvailabilityCheck {

    fun isAvailable(
        tool: ToolDefinition,
        vehicleModel: String?,
        softwareVersion: String?
    ): Boolean = isAvailable(tool.availability, vehicleModel, softwareVersion)

    fun isAvailable(
        availability: ToolAvailability,
        vehicleModel: String?,
        softwareVersion: String?
    ): Boolean {
        if (!availability.enabled) return false
        if (vehicleModel != null && availability.vehicleModels.isNotEmpty()) {
            val compatible = availability.vehicleModels.any { it == "*" || it == vehicleModel }
            if (!compatible) return false
        }
        if (softwareVersion != null && availability.softwareRange != null) {
            if (!VersionRange.matches(softwareVersion, availability.softwareRange)) return false
        }
        return true
    }
}
