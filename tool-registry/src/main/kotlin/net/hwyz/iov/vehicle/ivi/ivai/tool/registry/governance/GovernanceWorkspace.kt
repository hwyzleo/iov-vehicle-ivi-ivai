package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.capability.CapabilityPack
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.RiskLevel
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolExecutionBinding
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolPolicy
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowPolicy
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowStep

/**
 * 治理工作区（IVI-IVAI-DSN-CR-009）。
 *
 * 聚合第一版治理基线的全部目录资产，并提供：
 *  - 数量统计（Tool/Workflow 按 Domain、Capability Pack）。
 *  - 运行时索引过滤：只有 APPROVED 且 BOUND 的 Tool/Workflow 才能进入
 *    可执行候选集合（IVAI-GOV-001 / IVAI-BINDING-001 阻止未就绪项）。
 *  - 从治理目录生成 ToolDefinition / WorkflowDefinition（Mock Adapter 桩）。
 *
 * DRAFT 条目可生成代码骨架与 Mock 桩用于开发与测试，但不得视为量产 APPROVED
 * 或真实车辆 Binding 已完成。
 */
object GovernanceWorkspace {

    val baseline: GovernanceBaseline = GovernanceBaseline(
        baselineVersion = "ivai-governance-v1",
        sourceCatalogVersion = "ivai-source-v1",
        generatedAt = java.time.Instant.parse("2026-09-06T00:00:00Z")
    )

    val packs: List<CapabilityPackGovernanceSpec> = CapabilityPackCatalogV1.ALL
    val tools: List<ToolGovernanceSpec> = ToolCatalogV1.ALL
    val workflows: List<WorkflowGovernanceSpec> = WorkflowCatalogV1.ALL
    val sourceCatalog: SourceCatalogStats = SourceCatalogStats()

    /** Manifest（actual 由构建流程计算）。 */
    val manifest: GovernanceManifest by lazy {
        GovernanceManifestBuilder.build(baseline, packs, tools, workflows, sourceCatalog)
    }

    /** 门禁结果。 */
    val validation: GovernanceValidationResult by lazy {
        GovernanceValidator(baseline, packs, tools, workflows, sourceCatalog).validate()
    }

    /** 运行时可执行索引：APPROVED + BOUND 的 Tool。 */
    fun runtimeIndexableTools(): List<ToolGovernanceSpec> =
        tools.filter { it.status.name == "APPROVED" && it.bindingStatus.name == "BOUND" }

    /** 运行时可执行索引：APPROVED + BOUND 的 Workflow。 */
    fun runtimeIndexableWorkflows(): List<WorkflowGovernanceSpec> =
        workflows.filter { it.status.name == "APPROVED" && it.bindingStatus.name == "BOUND" }

    /** 生成 ToolDefinition（Mock Binding：adapterId=mock-governed, methodId=toolId）。 */
    fun toToolDefinition(spec: ToolGovernanceSpec): ToolDefinition = ToolDefinition(
        toolId = spec.toolId,
        functionId = null,
        name = spec.name,
        description = "治理目录 ${spec.toolId}（${spec.operationType}），Mock 桩执行。",
        positiveExamples = emptyList(),
        negativeExamples = emptyList(),
        selectionPriority = 1,
        parameterSchema = "{ \"type\": \"object\", \"properties\": {}, \"required\": [] }",
        policy = ToolPolicy(riskLevel = riskLevelOf(spec.policySummary)),
        execution = ToolExecutionBinding(adapterId = MOCK_ADAPTER_ID, methodId = spec.toolId),
        domainId = spec.domainId,
        capabilityPackId = spec.capabilityPackId,
        supportedOperations = setOf(spec.operationType),
        governanceVersion = spec.governanceVersion
    )

    /**
     * 注册全部 160 个治理 Tool 到 [ToolRegistry]（IVI-IVAI-DSN-CR-009）。
     *
     * CR-009 兼容关系：保留现有 6 个空调 Tool 作为 P0 验证集，其余以
     * Mock 桩（mock-governed）注册，保证运行时链路（路由→校验→Policy→执行）
     * 对全部 160 个 Tool 可跑通；真实 Binding 由后续分阶段 CR 替换。
     */
    fun registerAllStubs(registry: ToolRegistry): ToolRegistry {
        tools.forEach { registry.register(toToolDefinition(it)) }
        return registry
    }

    /** Mock 桩适配器 ID（与 MockGovernedToolAdapter.adapterId 一致）。 */
    const val MOCK_ADAPTER_ID = "mock-governed"

    /**
     * 运行时桩启用的 18 个 Capability Pack（IVI-IVAI-DSN-CR-009）。
     *
     * 治理目录状态是 DRAFT，但为了让 160 个 Mock 桩在运行时链路可调通，此处把
     * 18 个 Pack 全部标记为 enabled + APPROVED（桩模式），toolIds 由 [ToolCatalogV1]
     * 按 packId 分组得出。真实量产前必须回到 DRAFT→APPROVED + Binding 门禁。
     */
    fun runtimePacks(): List<CapabilityPack> {
        val toolIdsByPack = tools.groupBy { it.capabilityPackId }.mapValues { (_, list) ->
            list.map { it.toolId }.toSet()
        }
        return packs.map { spec ->
            CapabilityPack(
                packId = spec.packId,
                domainId = spec.domainId,
                name = spec.name,
                toolIds = toolIdsByPack[spec.packId] ?: emptySet(),
                workflowIds = emptySet(),
                governanceVersion = "1.0",
                enabled = true,
                status = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus.APPROVED
            )
        }
    }

    /** 生成 WorkflowDefinition（Mock 步骤执行）。 */
    fun toWorkflowDefinition(spec: WorkflowGovernanceSpec): WorkflowDefinition = WorkflowDefinition(
        workflowId = spec.workflowId,
        domainIds = spec.domainIds,
        name = spec.name,
        description = spec.failurePolicy,
        triggerExamples = emptyList(),
        steps = spec.stepToolIds.mapIndexed { index, toolId ->
            WorkflowStep(
                stepId = "${spec.workflowId}.s${index + 1}",
                toolId = toolId
            )
        },
        policy = WorkflowPolicy(
            riskLevel = RiskLevel.MEDIUM,
            requiresConfirmation = spec.failurePolicy.contains("确认"),
            maxSteps = 8,
            allowedCrossDomain = spec.domainIds.size > 1
        ),
        governanceVersion = spec.governanceVersion,
        enabled = false,
        status = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus.DRAFT
    )

    /** 数量统计：Tool 按 Domain。 */
    fun toolCountByDomain(): Map<net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId, Int> =
        tools.groupingBy { it.domainId }.eachCount()

    /** 数量统计：Workflow 按 Owner Domain。 */
    fun workflowCountByOwnerDomain(): Map<net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId, Int> =
        workflows.groupingBy { it.ownerDomainId }.eachCount()

    private fun riskLevelOf(policySummary: String): RiskLevel = when {
        policySummary.startsWith("HIGH") -> RiskLevel.HIGH
        policySummary.startsWith("MEDIUM") -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }
}
