package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutionResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Prevents uncontrolled re-execution when the same (requestId, toolId, args)
 * is submitted again (IVI-IVAI-REQ-007 / EARS #7).
 */
class IdempotencyGuard {

    private val executed = ConcurrentHashMap<String, ToolExecutionResult>()

    fun keyFor(requestId: String, toolId: String, args: Map<String, Any?>): String {
        val serializedArgs = args.entries.sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }
        return "$requestId|$toolId|$serializedArgs"
    }

    fun find(key: String): ToolExecutionResult? = executed[key]

    fun record(key: String, result: ToolExecutionResult) {
        executed[key] = result
    }

    fun clear() = executed.clear()
}
