package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

/**
 * Tool lifecycle phases (IVI-IVAI-DSN-CR-001): REGISTERED → RETRIEVED → SELECTED →
 * VALIDATED → AUTHORIZED → EXECUTING → SUCCEEDED/FAILED/TIMEOUT → REPORTED.
 */
enum class ToolLifecyclePhase {
    REGISTERED,
    RETRIEVED,
    SELECTED,
    VALIDATED,
    AUTHORIZED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    TIMEOUT,
    REPORTED
}

data class ToolLifecycleEvent(
    val phase: ToolLifecyclePhase,
    val requestId: String,
    val toolId: String,
    val message: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Receives tool lifecycle events (implemented by observability / logging).
 */
fun interface ToolLifecycleListener {
    fun onToolLifecycle(event: ToolLifecycleEvent)
}
