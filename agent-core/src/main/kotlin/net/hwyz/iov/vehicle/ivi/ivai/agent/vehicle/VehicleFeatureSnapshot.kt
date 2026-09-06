package net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle

/**
 * 本车功能只读快照（IVI-IVAI-DSN-CR-009 治理目录的 UI 投影）。
 *
 * 供设置页「本车功能」只读展示：当前车辆的领域、能力包、工具与工作流
 * （中文名称 + 稳定代码）。快照使用纯 Kotlin 类型，由 [AgentService] 从
 * 治理目录 [GovernanceWorkspace] 组装，UI 永不通过该快照变更运行时。
 */
data class VehicleFeatureSnapshot(
    val baselineVersion: String,
    val sourceCatalogVersion: String,
    val domains: List<VehicleDomainInfo>,
    val packs: List<VehiclePackInfo>,
    val tools: List<VehicleToolInfo>,
    val workflows: List<VehicleWorkflowInfo>
) {
    val domainCount: Int get() = domains.size
    val packCount: Int get() = packs.size
    val toolCount: Int get() = tools.size
    val workflowCount: Int get() = workflows.size
}

/** 业务领域（中文 label + BD 代码）。 */
data class VehicleDomainInfo(
    val code: String,
    val label: String
)

/** 能力包（packId + 中文名称 + 所属领域 + 配额 + 优先级）。 */
data class VehiclePackInfo(
    val packId: String,
    val name: String,
    val domainCode: String,
    val toolTarget: Int,
    val workflowTarget: Int,
    val priority: String
)

/** 工具（toolId + 中文名称 + 所属领域/能力包 + 操作类型）。 */
data class VehicleToolInfo(
    val toolId: String,
    val name: String,
    val domainCode: String,
    val packId: String,
    val operationType: String
)

/** 工作流（workflowId + 中文名称 + 所属领域 + 步骤 Tool）。 */
data class VehicleWorkflowInfo(
    val workflowId: String,
    val name: String,
    val ownerDomainCode: String,
    val domainCodes: List<String>,
    val stepToolIds: List<String>,
    val failurePolicy: String
)
