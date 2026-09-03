package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry

/**
 * Dispatches validated intents to the adapter bound in the tool definition and
 * reports lifecycle events (EXECUTING → SUCCEEDED/FAILED/TIMEOUT → REPORTED).
 */
class DefaultToolExecutor(
    private val registry: ToolRegistry,
    private val adapterRegistry: AdapterRegistry,
    private val lifecycleListener: ToolLifecycleListener? = null,
    private val executionTimeoutMs: Long = 10_000
) : ToolExecutor {

    override suspend fun execute(
        toolId: String,
        arguments: Map<String, Any?>,
        context: ExecutionContext
    ): ToolExecutionResult {
        val tool = registry.get(toolId)
        if (tool == null) {
            return ToolExecutionResult(
                requestId = context.requestId,
                toolId = toolId,
                status = ExecutionStatus.FAILED,
                message = "Unknown tool: $toolId",
                errorCode = "IVAI-TOOL-001"
            )
        }
        val adapter = adapterRegistry.get(tool.execution.adapterId)
        if (adapter == null) {
            return ToolExecutionResult(
                requestId = context.requestId,
                toolId = toolId,
                status = ExecutionStatus.FAILED,
                message = "Adapter not registered: ${tool.execution.adapterId}",
                errorCode = "IVAI-EXEC-001"
            )
        }

        lifecycle(ToolLifecyclePhase.EXECUTING, context, toolId)
        return try {
            val adapterResult = withTimeout(executionTimeoutMs) {
                adapter.execute(tool.execution.methodId, arguments, context)
            }
            // Normalize the result so toolId/requestId are always authoritative.
            val result = adapterResult.copy(requestId = context.requestId, toolId = toolId)
            val phase = if (result.status == ExecutionStatus.SUCCEEDED) {
                ToolLifecyclePhase.SUCCEEDED
            } else {
                ToolLifecyclePhase.FAILED
            }
            lifecycle(phase, context, toolId, result.message)
            lifecycle(ToolLifecyclePhase.REPORTED, context, toolId, result.message)
            result
        } catch (e: TimeoutCancellationException) {
            val result = ToolExecutionResult(
                requestId = context.requestId,
                toolId = toolId,
                status = ExecutionStatus.TIMEOUT,
                message = "Tool execution timed out after ${executionTimeoutMs}ms",
                errorCode = "IVAI-EXEC-001"
            )
            lifecycle(ToolLifecyclePhase.TIMEOUT, context, toolId, result.message)
            lifecycle(ToolLifecyclePhase.REPORTED, context, toolId, result.message)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val result = ToolExecutionResult(
                requestId = context.requestId,
                toolId = toolId,
                status = ExecutionStatus.FAILED,
                message = "Tool execution failed: ${e.message}",
                errorCode = "IVAI-EXEC-001"
            )
            lifecycle(ToolLifecyclePhase.FAILED, context, toolId, result.message)
            lifecycle(ToolLifecyclePhase.REPORTED, context, toolId, result.message)
            result
        }
    }

    private fun lifecycle(
        phase: ToolLifecyclePhase,
        context: ExecutionContext,
        toolId: String,
        message: String? = null
    ) {
        lifecycleListener?.onToolLifecycle(
            ToolLifecycleEvent(phase, context.requestId, toolId, message)
        )
    }
}
