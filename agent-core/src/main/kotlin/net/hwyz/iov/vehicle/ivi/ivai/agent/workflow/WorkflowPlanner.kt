package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import net.hwyz.iov.vehicle.ivi.ivai.agent.router.NormalizedInput
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowRegistry

/**
 * WorkflowPlanner（IVI-IVAI-DSN-CR-008 首期）：只能选择**已注册**的 Workflow
 * （[WorkflowDefinition]），不允许本地模型自由生成任意长执行计划。匹配依据为
 * 触发表达（triggerExamples / name）在 [allowedWorkflowIds]（Capability Pack
 * 收敛后的候选空间）内的唯一命中；参数预置在 Workflow 步骤模板中完成。
 */
class WorkflowPlanner(
    private val workflows: List<WorkflowDefinition> = WorkflowRegistry.AVAILABLE
) {

    fun match(
        input: NormalizedInput,
        allowedWorkflowIds: Set<String>
    ): WorkflowDefinition? {
        if (allowedWorkflowIds.isEmpty()) return null
        val normalized = input.normalized
        val matched = workflows.filter { candidate ->
            candidate.workflowId in allowedWorkflowIds &&
                candidate.available &&
                (candidate.triggerExamples.any { normalized.contains(it) } || normalized.contains(candidate.name))
        }
        return matched.singleOrNull()
    }
}
