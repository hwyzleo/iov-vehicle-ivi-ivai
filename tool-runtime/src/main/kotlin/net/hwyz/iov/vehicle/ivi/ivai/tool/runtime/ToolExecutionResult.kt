package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

enum class ExecutionStatus {
    SUCCEEDED,
    FAILED,
    TIMEOUT
}

/**
 * Execution context propagated from agent-core into the executor / adapter.
 */
data class ExecutionContext(
    val requestId: String,
    val sessionId: String,
    val source: String = "unknown",
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Unified tool execution result (IVI-IVAI-DSN-CR-001 mock execution contract).
 */
data class ToolExecutionResult(
    val requestId: String,
    val toolId: String,
    val status: ExecutionStatus,
    val message: String,
    val stateChanges: Map<String, Any?> = emptyMap(),
    val errorCode: String? = null,
    val executedAtMs: Long = System.currentTimeMillis()
)
