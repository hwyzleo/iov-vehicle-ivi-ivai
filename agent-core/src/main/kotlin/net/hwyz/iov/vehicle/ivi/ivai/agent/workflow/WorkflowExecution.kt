package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutionResult

/**
 * Workflow 执行状态机状态（IVI-IVAI-DSN-CR-008）：
 *   CREATED → VALIDATED → AUTHORIZED → RUNNING
 *     → STEP_RUNNING → STEP_SUCCEEDED / STEP_FAILED
 *     → COMPENSATING（可选）→ SUCCEEDED / PARTIALLY_SUCCEEDED / FAILED / CANCELLED
 */
enum class WorkflowExecutionState {
    CREATED,
    VALIDATED,
    AUTHORIZED,
    RUNNING,
    STEP_RUNNING,
    STEP_SUCCEEDED,
    STEP_FAILED,
    COMPENSATING,
    SUCCEEDED,
    PARTIALLY_SUCCEEDED,
    FAILED,
    CANCELLED
}

/** 单个步骤的执行结果。 */
data class WorkflowStepResult(
    val stepId: String,
    val toolId: String,
    val state: WorkflowExecutionState,
    val message: String,
    val execution: ToolExecutionResult? = null,
    val errorCode: String? = null
)

/**
 * Workflow 执行结果（CR-008）。[state] 为终态：SUCCEEDED / PARTIALLY_SUCCEEDED /
 * FAILED / CANCELLED；[compensationResult] 在失败补偿后填充。
 */
data class WorkflowExecutionResult(
    val workflowId: String,
    val state: WorkflowExecutionState,
    val stepResults: List<WorkflowStepResult>,
    val compensationResult: WorkflowStepResult? = null,
    val errorCode: String? = null,
    val message: String
)

/** Workflow 校验结果（CR-008）。 */
sealed interface WorkflowValidationResult {
    data object Valid : WorkflowValidationResult
    data class Invalid(val errorCode: String, val reason: String) : WorkflowValidationResult
}
