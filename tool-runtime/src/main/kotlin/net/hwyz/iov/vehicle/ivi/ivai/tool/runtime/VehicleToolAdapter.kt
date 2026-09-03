package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

/**
 * Abstraction over a concrete vehicle backend (mock now, real SOA later).
 * agent-core only ever talks to adapters through this interface.
 */
interface VehicleToolAdapter {
    val adapterId: String

    suspend fun execute(
        methodId: String,
        arguments: Map<String, Any?>,
        context: ExecutionContext
    ): ToolExecutionResult

    /** Read-only snapshot used for precondition checks. */
    fun snapshot(): VehicleStateSnapshot
}
