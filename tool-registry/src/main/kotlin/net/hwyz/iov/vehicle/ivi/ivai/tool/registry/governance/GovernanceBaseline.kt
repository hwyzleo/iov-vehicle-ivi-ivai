package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import java.time.Instant

/**
 * 第一版完整治理基线（IVI-IVAI-DSN-CR-009）。
 *
 * [GovernanceBaseline] 只用于治理检查、报告和发布门禁，**不参与运行时 Tool 选择**；
 * 运行时实际数量由当前有效 CapabilitySnapshot 决定。数量基线存储于 Governance
 * Manifest，不作为代码中的硬编码数组长度或业务判断条件。
 *
 * 允许收敛区间（REQ-CR-009）：
 *  - Tool：140～190（规划基线 160，正式冻结前完成来源映射、归并、Schema/Binding 评审）
 *  - Workflow：15～25（规划基线 18，场景拆解后收敛）
 */
data class GovernanceBaseline(
    val baselineVersion: String,
    val domainTarget: Int = 10,
    val capabilityPackTarget: Int = 18,
    val toolTarget: Int = 160,
    val workflowTarget: Int = 18,
    val toolAllowedRange: IntRange = 140..190,
    val workflowAllowedRange: IntRange = 15..25,
    val sourceCatalogVersion: String,
    val generatedAt: Instant
)

/**
 * 单个业务领域的治理配额（IVI-IVAI-DSN-CR-009 领域配额表）。
 * 用于治理拆分、工作量估算、评测覆盖和 Manifest 校验。
 */
data class DomainQuota(
    val domainId: BusinessDomainId,
    val toolTarget: Int,
    val workflowTarget: Int,
    val capabilityPackIds: Set<String>
)

/**
 * 实施优先级（P0～P3，IVI-IVAI-DSN-CR-009 Tool/Workflow Catalog）。
 * P0 = 首期验证集；P1/P2/P3 为后续治理与实施批次。
 */
enum class ImplementationPriority(val label: String) {
    P0("P0"),
    P1("P1"),
    P2("P2"),
    P3("P3")
}
