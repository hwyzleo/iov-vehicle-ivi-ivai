package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.RiskLevel
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus

/**
 * Workflow 定义（IVI-IVAI-DSN-CR-008）。场景模式、组合命令和跨领域请求优先
 * 映射为版本化 Workflow；Workflow 表达「跨能力、多步骤、补偿逻辑」，区别于
 * 表达单一稳定动作的原子 Tool。
 *
 * 安全约束（首期）：WorkflowPlanner 只能选择已注册的 [WorkflowDefinition]，
 * 不允许本地模型自由生成任意长执行计划；步骤数由 [WorkflowPolicy.maxSteps]
 * 与 [WorkflowDefinition.maxSteps] 双重限制。
 */
data class WorkflowDefinition(
    val workflowId: String,
    val domainIds: Set<BusinessDomainId>,
    val name: String,
    val description: String = "",
    val triggerExamples: List<String> = emptyList(),
    val parameterSchema: String = "{}",
    val steps: List<WorkflowStep>,
    val policy: WorkflowPolicy = WorkflowPolicy(),
    val governanceVersion: String = "1.0",
    val enabled: Boolean = true,
    val status: GovernanceStatus = GovernanceStatus.APPROVED,
    val maxSteps: Int = WorkflowPolicy.DEFAULT_MAX_STEPS
) {
    /** 运行时可用的最低门槛：启用 + 已批准。 */
    val available: Boolean
        get() = enabled && status == GovernanceStatus.APPROVED
}

/**
 * 单个 Workflow 步骤：引用一个原子 Tool + 参数模板 + 前置条件 + 失败策略。
 * 每个步骤执行前必须重新完成 Tool 可用性、Schema、Policy 与确认检查
 * （IVI-IVAI-DSN-CR-008「步骤执行前重新检查」）。
 */
data class WorkflowStep(
    val stepId: String,
    val toolId: String,
    val argumentTemplate: Map<String, Any?> = emptyMap(),
    val preconditions: List<Condition> = emptyList(),
    val onFailure: FailureStrategy = FailureStrategy.ABORT,
    /** 失败补偿 Tool（可选）；[FailureStrategy.COMPENSATE] 时必需。 */
    val compensationToolId: String? = null,
    val compensationArguments: Map<String, Any?> = emptyMap(),
    /** 本步骤执行前是否需要用户确认。 */
    val confirm: Boolean = false
)

/** 步骤前置条件字段操作符。 */
enum class ConditionOp {
    EQUALS,
    NOT_EQUALS,
    EXISTS,
    GT,
    LT
}

/** 步骤前置条件：基于参数或车辆状态快照字段。 */
data class Condition(
    val field: String,
    val op: ConditionOp,
    val value: Any? = null
)

/** 步骤失败策略（首期子集）。 */
enum class FailureStrategy {
    /** 终止整个 Workflow。 */
    ABORT,
    /** 跳过当前步骤继续。 */
    SKIP,
    /** 执行补偿步骤后终止。 */
    COMPENSATE,
    /** 暂停并向用户追问 / 确认。 */
    ASK_USER
}

/**
 * Workflow 级策略：风险、确认、最大步骤数与跨领域组合限制。
 * 涉及高风险或不可补偿动作时整体拒绝或转云后回端侧复核。
 */
data class WorkflowPolicy(
    val riskLevel: RiskLevel = RiskLevel.MEDIUM,
    val requiresConfirmation: Boolean = true,
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    /** 是否允许跨业务领域组合步骤。 */
    val allowedCrossDomain: Boolean = false
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 8
    }
}
