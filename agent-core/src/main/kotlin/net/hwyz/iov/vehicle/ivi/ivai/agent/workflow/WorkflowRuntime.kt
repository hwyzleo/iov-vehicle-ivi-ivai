package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentOutput
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentRoute
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.Intent
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.valuesToJsonArgs
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.PolicyOutcome
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.FailureStrategy
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowStep
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionContext
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutionResult
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolLifecycleEvent
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolLifecycleListener
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolLifecyclePhase
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateSnapshot

/**
 * WorkflowRuntime（IVI-IVAI-DSN-CR-008 首期，作为 agent-core/workflow 内部实现，
 * 不新增 Gradle Module）：按 Workflow 状态机执行已注册 Workflow。
 *
 * 安全链路不降级：**每个步骤执行前**都重新完成：
 *  1. 参数 Schema 校验（默认值 / 归一化）；
 *  2. AgentPolicy 评估（风险 / 前置 / 确认）；
 *  3. 幂等检查（同一 requestId+tool+args 不重复执行）；
 *  4. 经 ToolExecutor 执行原子动作（最终到达适配器）。
 *
 * 失败策略：ABORT 终止 / SKIP 跳过继续 / COMPENSATE 执行补偿后终止 /
 * ASK_USER 暂停等待用户决策。
 */
class WorkflowRuntime(
    private val registry: ToolRegistry,
    private val toolValidator: ToolValidator,
    private val agentPolicy: AgentPolicyEngine,
    private val toolExecutor: ToolExecutor,
    private val workflowValidator: WorkflowValidator,
    private val vehicleStateProvider: VehicleStateProvider? = null,
    private val idempotencyGuard: IdempotencyGuard = IdempotencyGuard(),
    private val lifecycleListener: ToolLifecycleListener? = null
) {

    /**
     * 执行一个 Workflow。
     *
     * @param arguments 用户/调用方提供的参数，与步骤模板合并（模板优先）。
     * @param approval 确认门禁回调：步骤需要确认时调用；返回 true 授权执行，
     *   false 则按未获确认失败（IVAI-POLICY-001）。
     */
    suspend fun execute(
        workflow: WorkflowDefinition,
        arguments: Map<String, Any?>,
        context: ExecutionContext,
        approval: suspend (WorkflowStep) -> Boolean = { true }
    ): WorkflowExecutionResult {
        val machine = WorkflowStateMachine()

        val validation = workflowValidator.validate(workflow, vehicleModel = null, softwareVersion = null)
        if (validation is WorkflowValidationResult.Invalid) {
            machine.transition(WorkflowExecutionState.FAILED)
            return WorkflowExecutionResult(
                workflowId = workflow.workflowId,
                state = WorkflowExecutionState.FAILED,
                stepResults = emptyList(),
                errorCode = validation.errorCode,
                message = validation.reason
            )
        }
        machine.transition(WorkflowExecutionState.VALIDATED)
        machine.transition(WorkflowExecutionState.AUTHORIZED)
        machine.transition(WorkflowExecutionState.RUNNING)

        val results = mutableListOf<WorkflowStepResult>()
        var terminal: WorkflowExecutionResult? = null

        for (step in workflow.steps) {
            val current = machine.state
            if (current == WorkflowExecutionState.CANCELLED || current == WorkflowExecutionState.FAILED) break
            machine.transition(WorkflowExecutionState.STEP_RUNNING)

            val stepResult = executeStep(workflow, step, arguments, context, approval)
            results += stepResult

            if (stepResult.state == WorkflowExecutionState.STEP_SUCCEEDED) {
                machine.transition(WorkflowExecutionState.STEP_SUCCEEDED)
                machine.transition(WorkflowExecutionState.RUNNING)
                continue
            }

            // 步骤失败：按失败策略处理。
            machine.transition(WorkflowExecutionState.STEP_FAILED)
            when (step.onFailure) {
                FailureStrategy.SKIP -> {
                    machine.transition(WorkflowExecutionState.RUNNING)
                    continue
                }
                FailureStrategy.ABORT -> {
                    machine.transition(WorkflowExecutionState.FAILED)
                    terminal = WorkflowExecutionResult(
                        workflowId = workflow.workflowId,
                        state = WorkflowExecutionState.FAILED,
                        stepResults = results,
                        errorCode = stepResult.errorCode ?: "IVAI-WORKFLOW-003",
                        message = "步骤 ${step.stepId} 失败，Workflow 终止"
                    )
                    break
                }
                FailureStrategy.ASK_USER -> {
                    machine.transition(WorkflowExecutionState.CANCELLED)
                    terminal = WorkflowExecutionResult(
                        workflowId = workflow.workflowId,
                        state = WorkflowExecutionState.CANCELLED,
                        stepResults = results,
                        errorCode = stepResult.errorCode,
                        message = "步骤 ${step.stepId} 失败，等待用户决策"
                    )
                    break
                }
                FailureStrategy.COMPENSATE -> {
                    machine.transition(WorkflowExecutionState.COMPENSATING)
                    val compensation = runCompensation(step, context)
                    machine.transition(WorkflowExecutionState.FAILED)
                    terminal = WorkflowExecutionResult(
                        workflowId = workflow.workflowId,
                        state = WorkflowExecutionState.FAILED,
                        stepResults = results,
                        compensationResult = compensation,
                        errorCode = stepResult.errorCode ?: "IVAI-WORKFLOW-003",
                        message = if (compensation?.state == WorkflowExecutionState.STEP_SUCCEEDED) {
                            "步骤 ${step.stepId} 失败，已补偿；Workflow 终止"
                        } else {
                            "步骤 ${step.stepId} 失败且补偿失败（IVAI-WORKFLOW-003）"
                        }
                    )
                    break
                }
            }
        }

        terminal?.let { return it }

        val succeededCount = results.count { it.state == WorkflowExecutionState.STEP_SUCCEEDED }
        val finalState = when {
            succeededCount == workflow.steps.size -> WorkflowExecutionState.SUCCEEDED
            succeededCount == 0 -> WorkflowExecutionState.FAILED
            else -> WorkflowExecutionState.PARTIALLY_SUCCEEDED
        }
        machine.transition(finalState)
        return WorkflowExecutionResult(
            workflowId = workflow.workflowId,
            state = finalState,
            stepResults = results,
            message = if (finalState == WorkflowExecutionState.SUCCEEDED) {
                "「${workflow.name}」执行完成"
            } else {
                "「${workflow.name}」部分完成（${succeededCount}/${workflow.steps.size}）"
            }
        )
    }

    // ------------------------------------------------------------------ step

    private suspend fun executeStep(
        workflow: WorkflowDefinition,
        step: WorkflowStep,
        userArguments: Map<String, Any?>,
        context: ExecutionContext,
        approval: suspend (WorkflowStep) -> Boolean
    ): WorkflowStepResult {
        val tool = registry.get(step.toolId)
        if (tool == null) {
            return failed(step, "步骤引用未知 Tool：${step.toolId}", "IVAI-WORKFLOW-001")
        }
        val merged = LinkedHashMap<String, Any?>(userArguments)
        merged.putAll(step.argumentTemplate)

        // 1) Schema：默认值 / 归一化 + 校验。
        val values = toolValidator.applyDefaultsAndNormalize(tool, merged)
        val issues = toolValidator.validateArguments(tool, values)
        if (issues.isNotEmpty()) {
            emitLifecycle(ToolLifecyclePhase.FAILED, context.requestId, step.toolId, issues.first().message)
            return failed(step, "步骤 ${step.stepId} 参数校验失败：${issues.first().message}", "IVAI-TOOL-002")
        }

        // 2) Policy：风险 / 前置 / 确认。
        val intent = Intent(toolId = step.toolId, arguments = valuesToJsonArgs(values))
        val output = AgentOutput(
            route = AgentRoute.LOCAL_TOOL.name,
            intents = listOf(intent),
            riskLevel = tool.policy.riskLevel.name.lowercase(),
            needConfirmation = step.confirm
        )
        val outcome = agentPolicy.evaluate(
            listOf(intent), output, vehicleState(), confirmationGranted = false
        )
        when (outcome) {
            is PolicyOutcome.NeedsConfirmation -> {
                if (!approval(step)) {
                    emitLifecycle(ToolLifecyclePhase.FAILED, context.requestId, step.toolId, "workflow step not approved")
                    return failed(step, "步骤 ${step.stepId}「${tool.name}」未获确认", "IVAI-POLICY-001")
                }
            }
            is PolicyOutcome.Denied -> {
                emitLifecycle(ToolLifecyclePhase.FAILED, context.requestId, step.toolId, outcome.message)
                return failed(step, "步骤 ${step.stepId} 未获授权：${outcome.message}", outcome.errorCode.code)
            }
            PolicyOutcome.Authorized -> Unit
        }

        // 3) 幂等：同一 (requestId, toolId, args) 不重复执行。
        val key = idempotencyGuard.keyFor(context.requestId, step.toolId, values)
        val cached = idempotencyGuard.find(key)
        if (cached != null) {
            emitLifecycle(ToolLifecyclePhase.REPORTED, context.requestId, step.toolId, "replayed")
            return if (cached.status == ExecutionStatus.SUCCEEDED) {
                WorkflowStepResult(
                    stepId = step.stepId, toolId = step.toolId,
                    state = WorkflowExecutionState.STEP_SUCCEEDED,
                    message = "${cached.message}（重复请求，返回上次结果）", execution = cached
                )
            } else {
                failed(step, cached.message, cached.errorCode ?: "IVAI-EXEC-001", cached)
            }
        }

        // 4) 执行。
        emitLifecycle(ToolLifecyclePhase.AUTHORIZED, context.requestId, step.toolId)
        val exec = toolExecutor.execute(
            toolId = step.toolId,
            arguments = values,
            context = context
        )
        idempotencyGuard.record(key, exec)
        emitLifecycle(
            if (exec.status == ExecutionStatus.SUCCEEDED) ToolLifecyclePhase.SUCCEEDED else ToolLifecyclePhase.FAILED,
            context.requestId, step.toolId, exec.message
        )
        return if (exec.status == ExecutionStatus.SUCCEEDED) {
            WorkflowStepResult(
                stepId = step.stepId, toolId = step.toolId,
                state = WorkflowExecutionState.STEP_SUCCEEDED,
                message = exec.message, execution = exec
            )
        } else {
            failed(step, exec.message, exec.errorCode ?: "IVAI-EXEC-001", exec)
        }
    }

    /** 补偿步骤（COMPENSATE 失败策略）。 */
    private suspend fun runCompensation(
        step: WorkflowStep,
        context: ExecutionContext
    ): WorkflowStepResult? {
        val compId = step.compensationToolId ?: return null
        val compTool = registry.get(compId)
        if (compTool == null) {
            return WorkflowStepResult(
                stepId = "comp.${step.stepId}", toolId = compId,
                state = WorkflowExecutionState.STEP_FAILED,
                message = "补偿 Tool 未知：$compId", errorCode = "IVAI-WORKFLOW-001"
            )
        }
        val values = toolValidator.applyDefaultsAndNormalize(compTool, step.compensationArguments)
        val issues = toolValidator.validateArguments(compTool, values)
        if (issues.isNotEmpty()) {
            return WorkflowStepResult(
                stepId = "comp.${step.stepId}", toolId = compId,
                state = WorkflowExecutionState.STEP_FAILED,
                message = "补偿参数校验失败：${issues.first().message}", errorCode = "IVAI-TOOL-002"
            )
        }
        val exec = toolExecutor.execute(compId, values, context)
        emitLifecycle(
            if (exec.status == ExecutionStatus.SUCCEEDED) ToolLifecyclePhase.SUCCEEDED else ToolLifecyclePhase.FAILED,
            context.requestId, compId, exec.message
        )
        return WorkflowStepResult(
            stepId = "comp.${step.stepId}", toolId = compId,
            state = if (exec.status == ExecutionStatus.SUCCEEDED) {
                WorkflowExecutionState.STEP_SUCCEEDED
            } else {
                WorkflowExecutionState.STEP_FAILED
            },
            message = exec.message, execution = exec, errorCode = exec.errorCode
        )
    }

    private fun failed(
        step: WorkflowStep,
        message: String,
        errorCode: String,
        execution: ToolExecutionResult? = null
    ) = WorkflowStepResult(
        stepId = step.stepId,
        toolId = step.toolId,
        state = WorkflowExecutionState.STEP_FAILED,
        message = message,
        execution = execution,
        errorCode = errorCode
    )

    private fun vehicleState(): VehicleStateSnapshot? = vehicleStateProvider?.snapshot()

    private fun emitLifecycle(phase: ToolLifecyclePhase, requestId: String, toolId: String, message: String? = null) {
        lifecycleListener?.onToolLifecycle(ToolLifecycleEvent(phase, requestId, toolId, message))
    }
}
