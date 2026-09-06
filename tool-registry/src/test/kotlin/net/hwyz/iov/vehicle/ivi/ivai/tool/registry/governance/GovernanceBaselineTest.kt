package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-009 验证设计 · 治理基线与领域配额：
 *  - GovernanceBaseline 默认目标（10/18/160/18）与允许区间（140..190 / 15..25）。
 *  - DomainQuotaCatalog BD01～BD10 合计 160 Tool / 18 Workflow。
 *  - 各领域配额与 REQ-CR-009 数量分配基线一致。
 */
class GovernanceBaselineTest {

    @Test
    fun `基线默认目标为 10 领域 18 能力包 160 Tool 18 Workflow`() {
        val baseline = GovernanceBaseline(
            baselineVersion = "ivai-governance-v1",
            sourceCatalogVersion = "ivai-source-v1",
            generatedAt = java.time.Instant.parse("2026-09-06T00:00:00Z")
        )
        assertEquals(10, baseline.domainTarget)
        assertEquals(18, baseline.capabilityPackTarget)
        assertEquals(160, baseline.toolTarget)
        assertEquals(18, baseline.workflowTarget)
        assertEquals(140..190, baseline.toolAllowedRange)
        assertEquals(15..25, baseline.workflowAllowedRange)
    }

    @Test
    fun `领域配额合计为 160 Tool 与 18 Workflow`() {
        assertEquals(10, DomainQuotaCatalog.QUOTAS.size)
        assertEquals(160, DomainQuotaCatalog.toolTargetSum)
        assertEquals(18, DomainQuotaCatalog.workflowTargetSum)
        // 每个领域都有配额，且覆盖全部 BD01～BD10。
        assertEquals(BusinessDomainId.entries.toSet(), DomainQuotaCatalog.QUOTAS.map { it.domainId }.toSet())
    }

    @Test
    fun `各领域配额与需求基线一致`() {
        assertEquals(22, DomainQuotaCatalog.toolTargetOf(BusinessDomainId.CABIN_COMFORT))
        assertEquals(2, DomainQuotaCatalog.workflowTargetOf(BusinessDomainId.CABIN_COMFORT))
        assertEquals(24, DomainQuotaCatalog.toolTargetOf(BusinessDomainId.BODY_CONTROL))
        assertEquals(1, DomainQuotaCatalog.workflowTargetOf(BusinessDomainId.BODY_CONTROL))
        assertEquals(28, DomainQuotaCatalog.toolTargetOf(BusinessDomainId.VEHICLE_DRIVING_CONFIG))
        assertEquals(4, DomainQuotaCatalog.workflowTargetOf(BusinessDomainId.VEHICLE_DRIVING_CONFIG))
        assertEquals(10, DomainQuotaCatalog.toolTargetOf(BusinessDomainId.ENERGY))
        assertEquals(2, DomainQuotaCatalog.workflowTargetOf(BusinessDomainId.ENERGY))
        assertEquals(8, DomainQuotaCatalog.toolTargetOf(BusinessDomainId.IMAGING_RECORDING))
        assertEquals(1, DomainQuotaCatalog.workflowTargetOf(BusinessDomainId.IMAGING_RECORDING))
        assertEquals(20, DomainQuotaCatalog.toolTargetOf(BusinessDomainId.NAVIGATION_TRAVEL))
        assertEquals(2, DomainQuotaCatalog.workflowTargetOf(BusinessDomainId.NAVIGATION_TRAVEL))
        assertEquals(8, DomainQuotaCatalog.toolTargetOf(BusinessDomainId.COMMUNICATION))
        assertEquals(1, DomainQuotaCatalog.workflowTargetOf(BusinessDomainId.COMMUNICATION))
        assertEquals(24, DomainQuotaCatalog.toolTargetOf(BusinessDomainId.MEDIA_ENTERTAINMENT))
        assertEquals(2, DomainQuotaCatalog.workflowTargetOf(BusinessDomainId.MEDIA_ENTERTAINMENT))
        assertEquals(10, DomainQuotaCatalog.toolTargetOf(BusinessDomainId.APP_SYSTEM))
        assertEquals(1, DomainQuotaCatalog.workflowTargetOf(BusinessDomainId.APP_SYSTEM))
        assertEquals(6, DomainQuotaCatalog.toolTargetOf(BusinessDomainId.INFORMATION_SERVICE))
        assertEquals(2, DomainQuotaCatalog.workflowTargetOf(BusinessDomainId.INFORMATION_SERVICE))
    }

    @Test
    fun `配额能力包引用均在 Pack 目录内`() {
        val packIds = CapabilityPackCatalogV1.ALL.map { it.packId }.toSet()
        for (quota in DomainQuotaCatalog.QUOTAS) {
            assertTrue(quota.capabilityPackIds.all { it in packIds }, "Domain ${quota.domainId.code} 引用未知 Pack")
        }
    }

    @Test
    fun `未知领域无配额时返回零`() {
        assertTrue(DomainQuotaCatalog.QUOTAS.isNotEmpty())
        assertEquals(10, DomainQuotaCatalog.QUOTAS.size)
    }
}
