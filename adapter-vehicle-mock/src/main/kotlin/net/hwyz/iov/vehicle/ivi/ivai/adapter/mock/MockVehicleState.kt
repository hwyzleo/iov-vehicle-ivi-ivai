package net.hwyz.iov.vehicle.ivi.ivai.adapter.mock

/**
 * In-memory simulated vehicle climate state (IVI-IVAI-DSN-CR-001).
 */
class MockVehicleState {
    var powerOn: Boolean = false
    var driverTemperature: Double = 24.0
    var passengerTemperature: Double = 24.0
    var frontTemperature: Double = 24.0
    var rearTemperature: Double = 24.0
    var lastExecution: String? = null

    fun snapshotValues(): Map<String, Any?> = mapOf(
        "powerOn" to powerOn,
        "driverTemperature" to driverTemperature,
        "passengerTemperature" to passengerTemperature,
        "frontTemperature" to frontTemperature,
        "rearTemperature" to rearTemperature
    )
}
