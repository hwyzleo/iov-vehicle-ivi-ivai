package net.hwyz.iov.vehicle.ivi.ivai.agent.capability

import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouteDecision
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.AgentContext
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.capability.CapabilityCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.capability.CapabilityPack
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionRange

/**
 * 领域过滤结果原因（CR-008）：用于可观测性与降级诊断。
 */
enum class PackFilterReason {
    DOMAIN_MISMATCH,
    VEHICLE_MISMATCH,
    VERSION_MISMATCH,
    FEATURE_MISSING,
    GOVERNANCE_NOT_APPROVED,
    NOT_ENABLED
}

/**
 * 不可变 Capability Snapshot（IVI-IVAI-DSN-CR-008）。同一请求后续的路由、
 * Prompt 与执行必须使用同一快照，保证请求内一致性。
 *
 * [filteredToolIds] / [filteredWorkflowIds] 是选中 Pack 收敛后的候选空间，
 * L0/L1 检索只能在该空间内进行。
 */
data class CapabilitySnapshot(
    val packs: List<CapabilityPack>,
    val packVersion: String,
    val filteredToolIds: Set<String>,
    val filteredWorkflowIds: Set<String>,
    val filters: List<PackFilterReason>,
    val vehicleModel: String?,
    val softwareVersion: String?
) {
    companion object {
        val EMPTY = CapabilitySnapshot(
            packs = emptyList(),
            packVersion = "0",
            filteredToolIds = emptySet(),
            filteredWorkflowIds = emptySet(),
            filters = emptyList(),
            vehicleModel = null,
            softwareVersion = null
        )
    }
}

/**
 * CapabilityPackSelector（IVI-IVAI-DSN-CR-008）：在 Domain Router 之后、
 * L0/L1 检索之前，按业务领域、车型、软件版本、能力开关（Feature Flag）与
 * 治理状态过滤 Capability Pack，生成 [CapabilitySnapshot]。
 *
 * 过滤顺序：领域 → 车型 → 软件版本 → 能力开关 → 治理状态（先过滤再检索；
 * 首期按 P0 只启用 cabin.climate，其余 Pack 为治理定义不会被选中）。
 */
class CapabilityPackSelector(
    private val catalog: List<CapabilityPack> = CapabilityCatalog.AVAILABLE
) {

    fun select(
        decision: DomainRouteDecision,
        context: AgentContext,
        enabledFeatures: Set<String> = emptySet()
    ): CapabilitySnapshot {
        if (!decision.classified || decision.candidates.isEmpty()) {
            return CapabilitySnapshot.EMPTY
        }
        val domainIds = decision.candidates.map { it.domainId }.toSet()
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
                filters += PackFilterReason.GOVERNANCE_NOT_APPROVED
                keep = false
            }
            keep
        }

        val version = selected.maxOfOrNull { it.governanceVersion } ?: "0"
        return CapabilitySnapshot(
            packs = selected,
            packVersion = version,
            filteredToolIds = selected.flatMap { it.toolIds }.toSet(),
            filteredWorkflowIds = selected.flatMap { it.workflowIds }.toSet(),
            filters = filters.distinct(),
            vehicleModel = context.vehicleModel,
            softwareVersion = context.softwareVersion
        )
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
}
