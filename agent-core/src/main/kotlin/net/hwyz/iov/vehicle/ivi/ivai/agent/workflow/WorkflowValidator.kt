package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.FailureStrategy
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition

/**
 * WorkflowValidator（IVI-IVAI-DSN-CR-008 首期）：在 Workflow 进入执行前校验
 * 结构合法性与策略限制：
 *  - 治理状态必须 APPROVED + 启用（IVAI-GOV-001）。
 *  - 步骤数不得超过 min(定义 maxSteps, 策略 maxSteps)（IVAI-WORKFLOW-002）。
 *  - 未注册 / 步骤引用未知 Tool / 结构非法（IVAI-WORKFLOW-001）。
 *  - 跨领域组合需策略允许；COMPENSATE 策略必须带补偿 Tool。
 */
class WorkflowValidator(
    private val registry: ToolRegistry
) {

    fun validate(
        workflow: WorkflowDefinition,
        vehicleModel: String? = null,
        softwareVersion: String? = null
    ): WorkflowValidationResult {
        if (!workflow.available) {
            return WorkflowValidationResult.Invalid(
                "IVAI-GOV-001", "Workflow 治理状态不允许进入运行时：${workflow.workflowId}"
            )
        }
        val maxSteps = minOf(workflow.maxSteps, workflow.policy.maxSteps)
        if (workflow.steps.isEmpty()) {
            return WorkflowValidationResult.Invalid("IVAI-WORKFLOW-001", "Workflow 无步骤：${workflow.workflowId}")
        }
        if (workflow.steps.size > maxSteps) {
            return WorkflowValidationResult.Invalid(
                "IVAI-WORKFLOW-002", "Workflow 步骤数超过策略限制：${workflow.steps.size} > $maxSteps"
            )
        }
        // 步骤引用必须存在且属于已绑定 Tool（下一步 CR 校验 Binding）。
        for (step in workflow.steps) {
            if (registry.get(step.toolId) == null) {
                return WorkflowValidationResult.Invalid(
                    "IVAI-WORKFLOW-001", "步骤 ${step.stepId} 引用未知 Tool：${step.toolId}"
                )
            }
            if (step.onFailure == FailureStrategy.COMPENSATE && step.compensationToolId == null) {
                return WorkflowValidationResult.Invalid(
                    "IVAI-WORKFLOW-001", "步骤 ${step.stepId} 声明 COMPENSATE 但缺少补偿 Tool"
                )
            }
            step.compensationToolId?.let { compId ->
                if (registry.get(compId) == null) {
                    return WorkflowValidationResult.Invalid(
                        "IVAI-WORKFLOW-001", "步骤 ${step.stepId} 补偿 Tool 未知：$compId"
                    )
                }
            }
        }
        // 跨领域组合限制：默认不允许（露营模式步骤同域）。
        if (!workflow.policy.allowedCrossDomain) {
            val domains = workflow.steps.mapNotNull { registry.get(it.toolId)?.domainId }.toSet()
            if (domains.size > 1) {
                return WorkflowValidationResult.Invalid(
                    "IVAI-WORKFLOW-002", "跨领域组合未获策略允许：${domains}"
                )
            }
        }
        return WorkflowValidationResult.Valid
    }
}
