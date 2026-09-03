package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

/**
 * Registry mapping adapterId → VehicleToolAdapter.
 */
class AdapterRegistry {

    private val adapters = LinkedHashMap<String, VehicleToolAdapter>()

    fun register(adapter: VehicleToolAdapter): AdapterRegistry {
        require(adapters.put(adapter.adapterId, adapter) == null) {
            "Duplicate adapter registration: ${adapter.adapterId}"
        }
        return this
    }

    fun get(adapterId: String): VehicleToolAdapter? = adapters[adapterId]

    fun adapterIds(): Set<String> = adapters.keys
}
