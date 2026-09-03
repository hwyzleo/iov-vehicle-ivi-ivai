package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions

/**
 * Binds a tool to a concrete vehicle adapter + method, keeping agent-core
 * decoupled from the physical vehicle implementation.
 */
data class ToolExecutionBinding(
    val adapterId: String,
    val methodId: String
)
