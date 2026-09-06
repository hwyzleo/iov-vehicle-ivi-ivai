package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.capability

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionConstraint
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus

/**
 * Capability Pack（IVI-IVAI-DSN-CR-008）：按业务领域聚合的一组 Tool / Workflow，
 * 附带车型、软件版本、能力开关与权限约束。CapabilityPackSelector 在
 * DomainRouter 之后、L0/L1 检索之前应用 Pack 过滤，把候选空间收敛到受控集合。
 *
 * 同一请求后续的路由、Prompt 与执行必须使用同一 [CapabilitySnapshot]，
 * 保证请求内一致性（设计：同一运行时快照只能解析出一个有效 Binding）。
 */
data class CapabilityPack(
    val packId: String,
    val domainId: BusinessDomainId,
    val name: String,
    val toolIds: Set<String> = emptySet(),
    val workflowIds: Set<String> = emptySet(),
    val applicableVehicles: VersionConstraint = VersionConstraint(),
    val applicableSoftware: VersionConstraint = VersionConstraint(),
    val requiredFeatures: Set<String> = emptySet(),
    val governanceVersion: String = "1.0",
    val enabled: Boolean = true,
    val status: GovernanceStatus = GovernanceStatus.APPROVED
) {
    /** 运行时可用的最低门槛：启用 + 已批准治理。 */
    val available: Boolean
        get() = enabled && status == GovernanceStatus.APPROVED
}
