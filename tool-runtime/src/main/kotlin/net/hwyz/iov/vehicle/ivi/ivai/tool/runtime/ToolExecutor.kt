package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

/**
 * Unified tool execution contract (IVI-IVAI-DSN-CR-001).
 */
interface ToolExecutor {
    suspend fun execute(
        toolId: String,
        arguments: Map<String, Any?>,
        context: ExecutionContext
    ): ToolExecutionResult
}
