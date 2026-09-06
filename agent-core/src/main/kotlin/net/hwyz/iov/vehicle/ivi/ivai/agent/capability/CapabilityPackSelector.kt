package net.hwyz.iov.vehicle.ivi.ivai.agent.capability

import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouteDecision
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.AgentContext
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.capability.CapabilityCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.capability.CapabilityPack
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionRange
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.DefaultRuntimeCapabilityAssembler
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceRuntimeMode
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.RuntimeCapabilityAssembler
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.RuntimeCapabilitySet
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.RuntimeEnvironment
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ToolAliasCatalog

/**
 * 领域过滤结果原因（CR-008 + CR-010）：用于可观测性与降级诊断。
 * [STUB_EXEMPTED] 为 CR-010 开发桩模式：DRAFT Pack 因豁免进入 STUB 候选。
 */
enum class PackFilterReason {
    DOMAIN_MISMATCH,
    VEHICLE_MISMATCH,
    VERSION_MISMATCH,
    FEATURE_MISSING,
    GOVERNANCE_NOT_APPROVED,
    NOT_ENABLED,
    STUB_EXEMPTED
}

/**
 * 不可变 Capability Snapshot（CR-008 + CR-010）。同一请求后续的路由、Prompt 与
 * 执行必须使用同一快照，保证请求内一致性。
 *
 * CR-010：L0/L1 是请求解析路径而非 Tool 分类。快照不再把 Tool 预分层为
 * L0/L1 白名单，而是输出选中 Pack 内全部运行时可执行 Tool 的统一
 * [runtimeCandidateToolIds]（canonical、已去重）。[filteredToolIds] /
 * [filteredWorkflowIds] 为 CR-008 时代的预分层字段，现作为
 * [runtimeCandidateToolIds] / [runtimeCandidateWorkflowIds] 的兼容别名保留。
 */
data class CapabilitySnapshot(
    val packs: List<CapabilityPack>,
    val packVersion: String,
    /** @deprecated CR-010 已由 [runtimeCandidateToolIds] 取代（兼容 CR-008 快照字段）。 */
    @Deprecated("CR-010：使用 runtimeCandidateToolIds（统一候选集）")
    val filteredToolIds: Set<String>,
    /** @deprecated CR-010 已由 [runtimeCandidateWorkflowIds] 取代。 */
    @Deprecated("CR-010：使用 runtimeCandidateWorkflowIds（统一候选集）")
    val filteredWorkflowIds: Set<String>,
    val filters: List<PackFilterReason>,
    val vehicleModel: String?,
    val softwareVersion: String?,
    // ---- CR-010 统一候选集 ----
    val selectedPackIds: Set<String>,
    val runtimeCandidateToolIds: Set<String>,
    val runtimeCandidateWorkflowIds: Set<String>,
    val governanceVersion: String,
    val migrationVersion: String?,
    val governanceRuntimeMode: String,
    val stubExemptedToolIds: Set<String> = emptySet()
) {
    /** 稳定可复现的候选集 Hash（CR-010 可观测性）。 */
    val runtimeCandidateToolIdsHash: String by lazy {
        RuntimeCapabilitySet.stableHash(runtimeCandidateToolIds)
    }

    companion object {
        val EMPTY = CapabilitySnapshot(
            packs = emptyList(),
            packVersion = "0",
            filteredToolIds = emptySet(),
            filteredWorkflowIds = emptySet(),
            filters = emptyList(),
            vehicleModel = null,
            softwareVersion = null,
            selectedPackIds = emptySet(),
            runtimeCandidateToolIds = emptySet(),
            runtimeCandidateWorkflowIds = emptySet(),
            governanceVersion = "0",
            migrationVersion = null,
            governanceRuntimeMode = "NONE"
        )
    }
}

/**
 * CapabilityPackSelector（CR-008 + CR-010）：在 Domain Router 之后、L0/L1 检索
 * 之前，按业务领域、车型、软件版本、能力开关（Feature Flag）与治理状态过滤
 * Capability Pack，再通过统一的 [RuntimeCapabilityAssembler] 计算
 * runtimeCandidateToolIds（canonical、去重），生成 [CapabilitySnapshot]。
 *
 * 兼容性：未注入 [runtimeAssembler] / [governanceCatalog] 时走 CR-008 旧路径
 * （候选 = 选中 Pack 的 Tool 原样并集），用于既有 CR-008 能力包过滤机制测试；
 * AgentService / CR-010 路由单测必须注入同一 Assembler（EARS #10）。
 */
class CapabilityPackSelector(
    private val catalog: List<CapabilityPack> = CapabilityCatalog.AVAILABLE,
    private val runtimeAssembler: RuntimeCapabilityAssembler? = null,
    private val governanceCatalog: GovernanceCatalog? = null,
    private val defaultEnvironment: RuntimeEnvironment = RuntimeEnvironment()
) {

    fun select(
        decision: DomainRouteDecision,
        context: AgentContext,
        enabledFeatures: Set<String> = emptySet(),
        environment: RuntimeEnvironment? = null
    ): CapabilitySnapshot {
        if (!decision.classified || decision.candidates.isEmpty()) {
            return CapabilitySnapshot.EMPTY
        }
        val domainIds = decision.candidates.map { it.domainId }.toSet()
        val env = environment ?: defaultEnvironment
        val filters = mutableListOf<PackFilterReason>()
        val selected = catalog.filter { pack ->
            var keep = true
            if (pack.domainId !in domainIds) {
                filters += PackFilterReason.DOMAIN_MISMATCH
                keep = false
            } else if (!vehicleAllowed(pack, context.vehicleModel)) {
                filters += PackFilterReason.VEHICLE_MISMATCH
                keep = false
            } else if (!softwareAllowed(pack, context.softwareVersion)) {
                filters += PackFilterReason.VERSION_MISMATCH
                keep = false
            } else if (!featuresAllowed(pack, enabledFeatures)) {
                filters += PackFilterReason.FEATURE_MISSING
                keep = false
            } else if (!pack.enabled) {
                filters += PackFilterReason.NOT_ENABLED
                keep = false
            } else if (pack.status != net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus.APPROVED) {
                // CR-010：DRAFT Pack 仅在 DEVELOPMENT_STUB + 豁免覆盖下放行（STUB 候选）。
                if (env.mode == GovernanceRuntimeMode.DEVELOPMENT_STUB &&
                    packExempted(pack, env)
                ) {
                    filters += PackFilterReason.STUB_EXEMPTED
                } else {
                    filters += PackFilterReason.GOVERNANCE_NOT_APPROVED
                    keep = false
                }
            }
            keep
        }

        val version = selected.maxOfOrNull { it.governanceVersion } ?: "0"
        val selectedPackIds = selected.map { it.packId }.toSet()

        // CR-010：统一运行时候选集经共享 RuntimeCapabilityAssembler 计算。
        val capabilitySet: RuntimeCapabilitySet = if (runtimeAssembler != null && governanceCatalog != null) {
            runtimeAssembler.assemble(
                catalog = governanceCatalog,
                aliases = ToolAliasCatalog,
                environment = env.copy(
                    vehicleModel = context.vehicleModel,
                    softwareVersion = context.softwareVersion,
                    enabledFeatures = enabledFeatures,
                    selectedPackIds = selectedPackIds
                )
            )
        } else {
            // CR-008 旧路径（无治理 Catalog）：候选 = 选中 Pack 的 Tool/Workflow 并集。
            RuntimeCapabilitySet(
                selectedPackIds = selectedPackIds,
                runtimeCandidateToolIds = selected.flatMap { it.toolIds }.toSet(),
                runtimeCandidateWorkflowIds = selected.flatMap { it.workflowIds }.toSet(),
                governanceVersion = version
            )
        }

        return CapabilitySnapshot(
            packs = selected,
            packVersion = version,
            filteredToolIds = capabilitySet.runtimeCandidateToolIds,
            filteredWorkflowIds = capabilitySet.runtimeCandidateWorkflowIds,
            filters = filters.distinct(),
            vehicleModel = context.vehicleModel,
            softwareVersion = context.softwareVersion,
            selectedPackIds = capabilitySet.selectedPackIds,
            runtimeCandidateToolIds = capabilitySet.runtimeCandidateToolIds,
            runtimeCandidateWorkflowIds = capabilitySet.runtimeCandidateWorkflowIds,
            governanceVersion = capabilitySet.governanceVersion,
            migrationVersion = capabilitySet.migrationVersion,
            governanceRuntimeMode = env.mode.name,
            stubExemptedToolIds = capabilitySet.stubExemptedToolIds
        )
    }

    /** 开发桩模式：Pack 是否被豁免覆盖（Pack 下任一 Tool 在豁免范围内即可放行）。 */
    private fun packExempted(pack: CapabilityPack, env: RuntimeEnvironment): Boolean {
        if (pack.toolIds.isEmpty()) return false
        val exempted = env.stubExemptions
            .filter { it.validFor(env.softwareVersion) }
            .flatMap { it.scopeToolIds }
            .toSet()
        return pack.toolIds.any { it in exempted }
    }

    private fun vehicleAllowed(pack: CapabilityPack, vehicleModel: String?): Boolean {
        val constraint = pack.applicableVehicles
        if (constraint.min == null && constraint.max == null) return true
        if (vehicleModel == null) return true // 未知车型不强制约束（P0 mock）
        val minOk = constraint.min == null || VersionRange.compare(vehicleModel, constraint.min!!) >= 0
        val maxOk = constraint.max == null || VersionRange.compare(vehicleModel, constraint.max!!) <= 0
        return minOk && maxOk
    }

    private fun softwareAllowed(pack: CapabilityPack, softwareVersion: String?): Boolean {
        if (softwareVersion == null) return true
        return VersionRange.matches(softwareVersion, constraintSpec(pack.applicableSoftware))
    }

    private fun constraintSpec(constraint: net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionConstraint): String {
        val min = constraint.min?.let { ">=$it" }
        val max = constraint.max?.let { "<$it" }
        return listOfNotNull(min, max).joinToString("&&")
    }

    private fun featuresAllowed(pack: CapabilityPack, enabledFeatures: Set<String>): Boolean =
        pack.requiredFeatures.isEmpty() || pack.requiredFeatures.all { it in enabledFeatures }

    companion object {
        /** 便捷装配：注入共享默认 Assembler 的 Selector（AgentService / 集成测试共用）。 */
        fun governed(
            catalog: List<CapabilityPack>,
            governanceCatalog: GovernanceCatalog,
            defaultEnvironment: RuntimeEnvironment = RuntimeEnvironment()
        ): CapabilityPackSelector = CapabilityPackSelector(
            catalog = catalog,
            runtimeAssembler = DefaultRuntimeCapabilityAssembler(),
            governanceCatalog = governanceCatalog,
            defaultEnvironment = defaultEnvironment
        )
    }
}
