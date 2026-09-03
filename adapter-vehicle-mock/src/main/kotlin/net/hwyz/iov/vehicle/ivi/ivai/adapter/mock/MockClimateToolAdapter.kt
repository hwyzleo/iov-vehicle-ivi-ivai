package net.hwyz.iov.vehicle.ivi.ivai.adapter.mock

import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionContext
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutionResult
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleToolAdapter

/**
 * Mock vehicle climate adapter. Executes the 6 first-batch climate tool methods
 * against an in-memory [MockVehicleState] and keeps the last execution record.
 */
class MockClimateToolAdapter(
    val state: MockVehicleState = MockVehicleState()
) : VehicleToolAdapter {

    override val adapterId: String = "mock-vehicle"

    override suspend fun execute(
        methodId: String,
        arguments: Map<String, Any?>,
        context: ExecutionContext
    ): ToolExecutionResult = synchronized(state) {
        when (methodId) {
            "powerOn" -> {
                state.powerOn = true
                state.lastExecution = "powerOn"
                success(context, methodId, "模拟执行：空调已开启", mapOf("powerOn" to true))
            }
            "powerOff" -> {
                state.powerOn = false
                state.lastExecution = "powerOff"
                success(context, methodId, "模拟执行：空调已关闭", mapOf("powerOn" to false))
            }
            "increaseTemperature" -> {
                val zone = zone(arguments)
                val step = (arguments["step"] as? Number)?.toInt() ?: 1
                val next = bump(zone, step)
                state.lastExecution = "increaseTemperature"
                success(context, methodId, "模拟执行：${zoneLabel(zone)}温度升高 ${step}℃", mapOf(zone to next))
            }
            "decreaseTemperature" -> {
                val zone = zone(arguments)
                val step = (arguments["step"] as? Number)?.toInt() ?: 1
                val next = bump(zone, -step)
                state.lastExecution = "decreaseTemperature"
                success(context, methodId, "模拟执行：${zoneLabel(zone)}温度降低 ${step}℃", mapOf(zone to next))
            }
            "setTemperature" -> {
                val zone = zone(arguments)
                val temp = (arguments["temperature"] as? Number)?.toDouble() ?: 24.0
                set(zone, temp)
                state.lastExecution = "setTemperature"
                success(context, methodId, "模拟执行：${zoneLabel(zone)}温度设定为 ${temp}℃", mapOf(zone to temp))
            }
            "queryStatus" -> {
                state.lastExecution = "queryStatus"
                success(context, methodId, "模拟执行：查询空调状态", state.snapshotValues())
            }
            else -> ToolExecutionResult(
                requestId = context.requestId,
                toolId = methodId,
                status = ExecutionStatus.FAILED,
                message = "Unknown mock method: $methodId",
                errorCode = "IVAI-EXEC-001"
            )
        }
    }

    override fun snapshot(): VehicleStateSnapshot = synchronized(state) {
        VehicleStateSnapshot(powerOn = state.powerOn)
    }

    private fun zone(arguments: Map<String, Any?>): String {
        val requested = arguments["position"] as? String
        return if (requested in VALID_ZONES) requested!! else "driver"
    }

    private fun zoneLabel(zone: String): String = when (zone) {
        "driver" -> "主驾"
        "passenger" -> "副驾"
        "front" -> "前排"
        "rear" -> "后排"
        else -> "主驾"
    }

    private fun bump(zone: String, delta: Int): Double {
        val current = value(zone)
        val next = (current + delta).coerceIn(16.0, 32.0)
        set(zone, next)
        return next
    }

    private fun set(zone: String, value: Double) {
        when (zone) {
            "driver" -> state.driverTemperature = value
            "passenger" -> state.passengerTemperature = value
            "front" -> state.frontTemperature = value
            "rear" -> state.rearTemperature = value
            else -> state.driverTemperature = value
        }
    }

    private fun value(zone: String): Double = when (zone) {
        "driver" -> state.driverTemperature
        "passenger" -> state.passengerTemperature
        "front" -> state.frontTemperature
        "rear" -> state.rearTemperature
        else -> state.driverTemperature
    }

    private fun success(
        context: ExecutionContext,
        methodId: String,
        message: String,
        stateChanges: Map<String, Any?>
    ) = ToolExecutionResult(
        requestId = context.requestId,
        toolId = methodId,
        status = ExecutionStatus.SUCCEEDED,
        message = message,
        stateChanges = stateChanges
    )

    private companion object {
        val VALID_ZONES = setOf("driver", "passenger", "front", "rear")
    }
}
