package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.vehicle

import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehicleFeatureSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService

/**
 * [VehicleInfoGateway] backed by the bound [AgentService].
 */
class ServiceVehicleInfoGateway(private val service: AgentService) : VehicleInfoGateway {
    override fun vehicleFeatureSnapshot(): VehicleFeatureSnapshot? =
        service.vehicleFeatureSnapshot()
}
