package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus

/**
 * 已注册 Workflow 目录（IVI-IVAI-DSN-CR-008）。
 *
 * 首期只注册完全由现有已绑定原子 Tool 组成的组合命令（空调域），作为
 * WorkflowPlanner / Validator / StateMachine 的验证样例；跨域场景（露营模式
 * 真实座椅/车窗等）下一版 CR 扩展。注册即受治理门禁（[GovernanceStatus]）。
 */
object WorkflowRegistry {

    /** 露营模式（P0 受限版）：完全由空调域原子 Tool 组合，可端到端执行。 */
    val CAMPING_MODE = WorkflowDefinition(
        workflowId = "cabin.camping_mode",
        domainIds = setOf(BusinessDomainId.CABIN_COMFORT),
        name = "露营模式",
        description = "开启空调并将温度设为舒适值（P0 空调域受限版；真实露营模式跨座椅/车窗等域，下一版扩展）。",
        triggerExamples = listOf("打开露营模式", "开启露营模式"),
        parameterSchema = """{"type":"object","properties":{"temperature":{"type":"number","minimum":16.0,"maximum":32.0}},"required":[]}""",
        steps = listOf(
            WorkflowStep(
                stepId = "s1.power_on",
                toolId = "climate.power_on",
                argumentTemplate = mapOf("position" to "driver"),
                onFailure = FailureStrategy.ABORT
            ),
            WorkflowStep(
                stepId = "s2.set_temp",
                toolId = "climate.temperature_set",
                argumentTemplate = mapOf("position" to "driver", "temperature" to 26),
                onFailure = FailureStrategy.COMPENSATE,
                compensationToolId = "climate.power_off",
                compensationArguments = mapOf("position" to "driver"),
                confirm = true
            )
        ),
        policy = WorkflowPolicy(
            riskLevel = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.RiskLevel.MEDIUM,
            requiresConfirmation = true,
            maxSteps = 2,
            allowedCrossDomain = false
        ),
        governanceVersion = "1.0",
        enabled = true,
        status = GovernanceStatus.APPROVED
    )

    /** 全部已注册 Workflow。 */
    val ALL: List<WorkflowDefinition> = listOf(CAMPING_MODE)

    fun get(workflowId: String): WorkflowDefinition? = ALL.firstOrNull { it.workflowId == workflowId }

    /** 当前运行环境可用的 Workflow（已批准 + 启用）。 */
    val AVAILABLE: List<WorkflowDefinition> = ALL.filter { it.available }
}
