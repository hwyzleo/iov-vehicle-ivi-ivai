package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.capability.CapabilityPack
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.RiskLevel
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolExecutionBinding
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolPolicy
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.AliasSourceType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.ToolAlias
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowPolicy
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowStep

/**
 * 治理工作区（IVI-IVAI-DSN-CR-009 + CR-010）。
 *
 * 聚合第一版治理基线的全部目录资产，并提供：
 *  - 数量统计（Tool/Workflow 按 Domain、Capability Pack）。
 *  - 统一只读 [catalog]（Pack/Tool/Workflow），供
 *    [RuntimeCapabilityAssembler] 计算运行时候选集（CR-010）。
 *  - 从治理目录生成 ToolDefinition / WorkflowDefinition（Mock Adapter 桩），
 *    并按 [ToolAliasCatalog] 附加确定性匹配画像与旧 ID Alias（CR-010）。
 *  - STRICT / DEVELOPMENT_STUB 双模式装配与 Release 门禁（CR-010）。
 *
 * DRAFT 条目可生成代码骨架与 Mock 桩用于开发与测试，但不得视为量产 APPROVED
 * 或真实车辆 Binding 已完成；开发桩必须通过显式豁免（STUB 模式）参与候选。
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

    /**
     * 统一只读治理目录（CR-010）。Pack 使用目录真实治理状态（默认 DRAFT），
     * 不再隐式强制 APPROVED；Stub 放行必须经 [devStubEnvironment] / 显式豁免。
     */
    val catalog: GovernanceCatalog by lazy {
        val toolIdsByPack = tools.groupBy { it.capabilityPackId }.mapValues { (_, list) ->
            list.map { it.toolId }.toSet()
        }
        GovernanceCatalog(
            packs = packs.map { capPackFromSpec(it, toolIdsByPack[it.packId] ?: emptySet()) },
            tools = tools,
            workflows = workflows
        )
    }

    // ------------------------------------------------------------------ L0 治理数据（Schema 枚举 / 正反例）

    /** L0 治理目录（懒加载，仅在需要 Schema 枚举或正反例时读取）。 */
    private val l0Catalog: L0GovernanceCatalogSpec by lazy { L0CatalogLoader.load() }

    /**
     * L0 目录批准的参数枚举值（toolId → 参数名 → JSON 字面量列表）。
     *
     * 来自 l0-governance-catalog.json 的 presetArguments（如 vehicle.brake_regen.set
     * level: OFF/LOW/STANDARD/HIGH），用于把泛型 `enum` 参数展开为可取枚举，供 L1
     * Prompt / Schema 校验使用。没有批准值的 `enum` 保持 string 类型、不携带枚举。
     */
    private val l0EnumValues: Map<String, Map<String, List<String>>> by lazy { buildL0EnumValues() }

    /** L0 目录批准正例（toolId → 正例列表），挂载到无 ToolAliasCatalog 画像的治理 Tool。 */
    private val l0PositiveExamples: Map<String, List<String>> by lazy {
        l0Catalog.tools.associate { tool -> tool.toolId to tool.positiveExamples }
    }

    /** L0 目录批准反例（toolId → 反例列表）。 */
    private val l0NegativeExamples: Map<String, List<String>> by lazy {
        l0Catalog.tools.associate { tool -> tool.toolId to tool.negativeExamples }
    }

    private fun buildL0EnumValues(): Map<String, Map<String, List<String>>> {
        val result = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
        l0Catalog.tools.forEach { tool ->
            tool.rules.forEach { rule ->
                rule.presetArguments.forEach { (arg, value) ->
                    val raw = value.toRawJsonLiteral()
                    val list = result.getOrPut(tool.toolId) { mutableMapOf() }.getOrPut(arg) { mutableListOf() }
                    if (raw !in list) list += raw
                }
            }
        }
        return result
    }

    private fun JsonElement.toRawJsonLiteral(): String = when (this) {
        is JsonPrimitive -> if (isString) "\"$content\"" else content
        else -> toString()
    }

    /** 运行时可执行索引：APPROVED + BOUND 的 Tool（治理目录视角）。 */
    fun runtimeIndexableTools(): List<ToolGovernanceSpec> =
        tools.filter { it.status == GovernanceStatus.APPROVED && it.bindingStatus.name == "BOUND" }

    /** 运行时可执行索引：APPROVED + BOUND 的 Workflow。 */
    fun runtimeIndexableWorkflows(): List<WorkflowGovernanceSpec> =
        workflows.filter { it.status == GovernanceStatus.APPROVED && it.bindingStatus.name == "BOUND" }

    /**
     * 生成 ToolDefinition（Mock Binding：adapterId=mock-governed, methodId=toolId）。
     * CR-010：按 [ToolAliasCatalog] 附加 DeterministicMatchProfile 规则、旧 ID Alias
     * 与正例（供 DomainRouter / L1 Retriever 识别明确表达）。
     */
    fun toToolDefinition(spec: ToolGovernanceSpec): ToolDefinition {
        val profile = ToolAliasCatalog.profileFor(spec.toolId)
        val base = ToolDefinition(
            toolId = spec.toolId,
            functionId = null,
            name = spec.name,
            description = "治理目录 ${spec.toolId}（${spec.operationType}），Mock 桩执行。",
            positiveExamples = l0PositiveExamples[spec.toolId].orEmpty(),
            negativeExamples = l0NegativeExamples[spec.toolId].orEmpty(),
            selectionPriority = 1,
            parameterSchema = GovernanceSchemaParser.parse(
                spec.parameterSchema,
                l0EnumValues[spec.toolId].orEmpty()
            ),
            policy = ToolPolicy(riskLevel = riskLevelOf(spec.policySummary)),
            execution = ToolExecutionBinding(adapterId = MOCK_ADAPTER_ID, methodId = spec.toolId),
            domainId = spec.domainId,
            capabilityPackId = spec.capabilityPackId,
            supportedOperations = setOf(spec.operationType),
            governanceVersion = spec.governanceVersion
        )
        if (profile == null) return base
        return base.copy(
            name = profile.name,
            positiveExamples = profile.positiveExamples,
            deterministicRules = profile.rules,
            aliases = profile.legacyAliases.map { it.toToolAlias() }
        )
    }

    /**
     * 注册全部 160 个治理 Tool 到 [ToolRegistry]（CR-009 + CR-010）。
     *
     * CR-010：统一候选集只含治理目录 Tool（canonical）。6 个旧空调 ID 不再作为
     * 独立可执行定义注册，而是作为 [ToolAliasCatalog] 的 LEGACY_TOOL_ID /
     * FUNCTION_ID Alias 映射到 canonical Tool；registerAllStubs 只为全部 160 个
     * canonical Tool 生成可执行定义（不计入额外的旧 ID）。
     */
    fun registerAllStubs(registry: ToolRegistry): ToolRegistry {
        tools.forEach { registry.register(toToolDefinition(it)) }
        return registry
    }

    /** Mock 桩适配器 ID（与 MockGovernedToolAdapter.adapterId 一致）。 */
    const val MOCK_ADAPTER_ID = "mock-governed"

    /**
     * 运行时 Capability Pack（CR-009 + CR-010）。
     *
     * 返回 18 个 Pack，使用目录**真实治理状态**（默认 DRAFT）；Pack 是否可选中
     * 由 CapabilityPackSelector 结合 [RuntimeEnvironment] 判定（STRICT 只收
     * APPROVED + enabled；DEVELOPMENT_STUB 经豁免放行白名单 DRAFT）。
     */
    fun runtimePacks(): List<CapabilityPack> {
        val toolIdsByPack = tools.groupBy { it.capabilityPackId }.mapValues { (_, list) ->
            list.map { it.toolId }.toSet()
        }
        return packs.map { capPackFromSpec(it, toolIdsByPack[it.packId] ?: emptySet()) }
    }

    private fun capPackFromSpec(spec: CapabilityPackGovernanceSpec, toolIds: Set<String>): CapabilityPack =
        CapabilityPack(
            packId = spec.packId,
            domainId = spec.domainId,
            name = spec.name,
            toolIds = toolIds,
            workflowIds = emptySet(),
            governanceVersion = spec.governanceVersion,
            enabled = true,
            status = spec.status
        )

    // ------------------------------------------------------------------ CR-010 开发桩与发布门禁

    /** 开发桩默认豁免：覆盖全部 160 个治理 Tool（仅 STUB / Mock Adapter）。 */
    fun devStubExemption(): StubExemption = StubExemption(
        exemptionId = "dev-stub-all-160",
        scopeToolIds = tools.map { it.toolId }.toSet(),
        reason = "开发桩：全量 160 治理 Tool 骨架验证（仅 Mock Adapter，DRAFT 状态保留）",
        owner = "ivai-core",
        startVersion = "0.0.0"
    )

    /** 开发桩运行环境（debug/test/mock-vehicle + 显式 Feature Flag）。 */
    fun devStubEnvironment(): RuntimeEnvironment = RuntimeEnvironment(
        mode = GovernanceRuntimeMode.DEVELOPMENT_STUB,
        stubExemptions = listOf(devStubExemption())
    )

    /**
     * Release 门禁（CR-010）：发布构建的唯一模式是 STRICT。
     * 检测到开发桩豁免、DRAFT 可执行项进入候选、真实车辆 Binding 或过期/无期限
     * 豁免都必须失败（IVAI-GOV-004 / IVAI-GOV-003）。
     */
    fun assertReleaseReady(environment: RuntimeEnvironment): GovernanceCatalog {
        require(environment.mode == GovernanceRuntimeMode.STRICT) {
            "${Cr010ErrorCodes.GOV_STUB_EXEMPTION_INVALID}: Release 构建必须使用 STRICT 模式，实际为 ${environment.mode}"
        }
        require(environment.stubExemptions.isEmpty()) {
            "${Cr010ErrorCodes.GOV_STUB_EXEMPTION_INVALID}: Release 构建不允许开发桩豁免: ${environment.stubExemptions.map { it.exemptionId }}"
        }
        return catalog
    }

    // ------------------------------------------------------------------ 目录统计

    /** 数量统计：Tool 按 Domain。 */
    fun toolCountByDomain(): Map<net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId, Int> =
        tools.groupingBy { it.domainId }.eachCount()

    /** 数量统计：Workflow 按 Owner Domain。 */
    fun workflowCountByOwnerDomain(): Map<net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId, Int> =
        workflows.groupingBy { it.ownerDomainId }.eachCount()

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
        status = GovernanceStatus.DRAFT
    )

    private fun riskLevelOf(policySummary: String): RiskLevel = when {
        policySummary.startsWith("HIGH") -> RiskLevel.HIGH
        policySummary.startsWith("MEDIUM") -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }
}

/** LegacyAliasMapping → 治理 [ToolAlias]（CR-010 旧 ID 保留为 Alias，非第二套可执行定义）。 */
internal fun LegacyAliasMapping.toToolAlias(): ToolAlias = ToolAlias(
    aliasId = aliasId,
    sourceType = sourceType,
    sourceValue = legacyId,
    originalDomain = "空调与舒适",
    mappedArguments = presetArguments,
    status = GovernanceStatus.APPROVED
)
