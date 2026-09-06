package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

/**
 * Workflow 状态机（IVI-IVAI-DSN-CR-008）：
 *
 *   CREATED → VALIDATED → AUTHORIZED → RUNNING
 *     → STEP_RUNNING → STEP_SUCCEEDED / STEP_FAILED
 *     → COMPENSATING（可选）
 *     → SUCCEEDED / PARTIALLY_SUCCEEDED / FAILED / CANCELLED
 *
 * 非法迁移返回 false（不抛异常），由 WorkflowRuntime 负责记录。
 */
class WorkflowStateMachine {

    var state: WorkflowExecutionState = WorkflowExecutionState.CREATED
        private set

    fun transition(target: WorkflowExecutionState): Boolean {
        val allowed = ALLOWED[state] ?: return false
        if (target !in allowed) return false
        state = target
        return true
    }

    fun canTransition(target: WorkflowExecutionState): Boolean {
        val allowed = ALLOWED[state] ?: return false
        return target in allowed
    }

    private companion object {
        val ALLOWED: Map<WorkflowExecutionState, Set<WorkflowExecutionState>> = mapOf(
            WorkflowExecutionState.CREATED to setOf(
                WorkflowExecutionState.VALIDATED, WorkflowExecutionState.CANCELLED, WorkflowExecutionState.FAILED
            ),
            WorkflowExecutionState.VALIDATED to setOf(
                WorkflowExecutionState.AUTHORIZED, WorkflowExecutionState.CANCELLED, WorkflowExecutionState.FAILED
            ),
            WorkflowExecutionState.AUTHORIZED to setOf(
                WorkflowExecutionState.RUNNING, WorkflowExecutionState.CANCELLED, WorkflowExecutionState.FAILED
            ),
            WorkflowExecutionState.RUNNING to setOf(
                WorkflowExecutionState.STEP_RUNNING, WorkflowExecutionState.SUCCEEDED,
                WorkflowExecutionState.PARTIALLY_SUCCEEDED, WorkflowExecutionState.FAILED, WorkflowExecutionState.CANCELLED
            ),
            WorkflowExecutionState.STEP_RUNNING to setOf(
                WorkflowExecutionState.STEP_SUCCEEDED, WorkflowExecutionState.STEP_FAILED,
                WorkflowExecutionState.CANCELLED
            ),
            WorkflowExecutionState.STEP_SUCCEEDED to setOf(
                WorkflowExecutionState.STEP_RUNNING, WorkflowExecutionState.SUCCEEDED,
                WorkflowExecutionState.PARTIALLY_SUCCEEDED, WorkflowExecutionState.FAILED
            ),
            WorkflowExecutionState.STEP_FAILED to setOf(
                WorkflowExecutionState.COMPENSATING, WorkflowExecutionState.FAILED,
                WorkflowExecutionState.PARTIALLY_SUCCEEDED, WorkflowExecutionState.CANCELLED,
                WorkflowExecutionState.STEP_RUNNING
            ),
            WorkflowExecutionState.COMPENSATING to setOf(
                WorkflowExecutionState.FAILED, WorkflowExecutionState.CANCELLED
            ),
            // 终态。
            WorkflowExecutionState.SUCCEEDED to emptySet(),
            WorkflowExecutionState.PARTIALLY_SUCCEEDED to emptySet(),
            WorkflowExecutionState.FAILED to emptySet(),
            WorkflowExecutionState.CANCELLED to emptySet()
        )
    }
}
