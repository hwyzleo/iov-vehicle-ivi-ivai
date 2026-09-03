package net.hwyz.iov.vehicle.ivi.ivai.observability

import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolLifecycleEvent
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolLifecycleListener

/**
 * Logs tool lifecycle events (REGISTERED → … → REPORTED).
 */
class ToolLifecycleLogger(
    private val out: (String) -> Unit = { println(it) }
) : ToolLifecycleListener {
    override fun onToolLifecycle(event: ToolLifecycleEvent) {
        out(
            buildString {
                append("[ivai-tool] ").append(event.phase)
                append(" ").append(event.toolId)
                append(" req=").append(event.requestId)
                event.message?.let { append(" | ").append(it) }
            }
        )
    }
}
