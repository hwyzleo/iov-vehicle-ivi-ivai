package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

/**
 * Supplies the current vehicle state to the agent (prompt context + policy preconditions).
 * Kept separate from [ToolExecutor] so agent-core stays decoupled from the adapter.
 */
fun interface VehicleStateProvider {
    fun snapshot(): VehicleStateSnapshot?
}
