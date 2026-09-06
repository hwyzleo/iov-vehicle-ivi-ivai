package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-009 验证设计 · Tool Catalog v1：
 *  - 160 个 Tool，ID 唯一、可查询。
 *  - 按 Domain 计数与领域配额一致。
 *  - 全部 DRAFT，不得进入运行时可执行索引（IVAI-GOV-001 / IVAI-BINDING-001）。
 */
class ToolCatalogV1Test {

    @Test
    fun `Tool 目录包含 160 个条目且 ID 唯一`() {
        assertEquals(160, ToolCatalogV1.ALL.size)
        assertEquals(160, ToolCatalogV1.ALL.map { it.toolId }.toSet().size)
        assertEquals(160, ToolCatalogV1.toolIds.size)
    }

    @Test
    fun `Tool 按领域计数与领域配额一致`() {
        val counts = ToolCatalogV1.countByDomain()
        assertEquals(160, counts.values.sum())
        for (quota in DomainQuotaCatalog.QUOTAS) {
            assertEquals(quota.toolTarget, counts[quota.domainId], "Domain ${quota.domainId.code} Tool 数应与配额一致")
        }
    }

    @Test
    fun `Tool 能力包引用均存在且 Pack 目标合计 160`() {
        val packCounts = ToolCatalogV1.countByPack()
        val packIds = CapabilityPackCatalogV1.ALL.map { it.packId }.toSet()
        assertTrue(packCounts.keys.all { it in packIds }, "存在引用未知 Pack 的 Tool")
        // Pack 目标是规划参考（REQ-CR-009：Capability Pack 允许 16～22 个范围细化）；
        // 实际逐项数量以 Tool Catalog 为准，合计必须为 160。
        assertEquals(160, packCounts.values.sum())
        assertEquals(160, CapabilityPackCatalogV1.toolTargetSum)
    }

    @Test
    fun `Tool 的 Domain 与 Pack 归属一致`() {
        for (tool in ToolCatalogV1.ALL) {
            val pack = CapabilityPackCatalogV1.get(tool.capabilityPackId)!!
            assertEquals(pack.domainId, tool.domainId, "Tool ${tool.toolId} Domain 与 Pack 归属不一致")
        }
    }

    @Test
    fun `全部 Tool 为 DRAFT 且无 Binding，不进入运行时索引`() {
        assertTrue(ToolCatalogV1.ALL.all { it.status == GovernanceStatus.DRAFT })
        assertTrue(ToolCatalogV1.ALL.all { it.bindingStatus.name == "NO_BINDING" })
        assertEquals(0, GovernanceWorkspace.runtimeIndexableTools().size)
    }

    @Test
    fun `可按 Tool ID 查询`() {
        assertEquals("调节温度", ToolCatalogV1.get("climate.temperature.adjust")?.name)
        assertEquals(BusinessDomainId.CABIN_COMFORT, ToolCatalogV1.get("climate.temperature.adjust")?.domainId)
        assertEquals(null, ToolCatalogV1.get("not.exist"))
    }
}
