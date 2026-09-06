package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-009 验证设计 · Workflow Catalog v1：
 *  - 18 个 Workflow，ID 唯一、可查询。
 *  - Owner Domain 计数与领域配额一致。
 *  - 步骤 Tool 全部可在 Tool Catalog 解析（TOOL_REFERENCE_INVALID 前置校验）。
 *  - 全部 DRAFT，不进入运行时可执行索引。
 */
class WorkflowCatalogV1Test {

    @Test
    fun `Workflow 目录包含 18 个条目且 ID 唯一`() {
        assertEquals(18, WorkflowCatalogV1.ALL.size)
        assertEquals(18, WorkflowCatalogV1.ALL.map { it.workflowId }.toSet().size)
        assertEquals(18, WorkflowCatalogV1.workflowIds.size)
    }

    @Test
    fun `Workflow 按 Owner Domain 计数与领域配额一致`() {
        val counts = WorkflowCatalogV1.countByOwnerDomain()
        assertEquals(18, counts.values.sum())
        for (quota in DomainQuotaCatalog.QUOTAS) {
            assertEquals(quota.workflowTarget, counts[quota.domainId] ?: 0, "Domain ${quota.domainId.code} Workflow 数应与配额一致")
        }
    }

    @Test
    fun `Workflow 步骤 Tool 全部可在 Tool 目录解析`() {
        val toolIds = ToolCatalogV1.toolIds
        for (wf in WorkflowCatalogV1.ALL) {
            assertTrue(
                wf.stepToolIds.all { it in toolIds },
                "Workflow ${wf.workflowId} 引用未知 Tool: ${wf.stepToolIds.filter { it !in toolIds }}"
            )
        }
    }

    @Test
    fun `Workflow 涉及领域均为 BD01 至 BD10`() {
        val allDomains = BusinessDomainId.entries.toSet()
        for (wf in WorkflowCatalogV1.ALL) {
            assertTrue(wf.ownerDomainId in allDomains, "${wf.workflowId} owner 非法")
            assertTrue(wf.domainIds.all { it in allDomains }, "${wf.workflowId} 涉及领域非法")
        }
    }

    @Test
    fun `全部 Workflow 为 DRAFT 且不进入运行时索引`() {
        assertTrue(WorkflowCatalogV1.ALL.all { it.status == GovernanceStatus.DRAFT })
        assertEquals(0, GovernanceWorkspace.runtimeIndexableWorkflows().size)
    }

    @Test
    fun `可按键查询`() {
        assertEquals("恶劣天气出行", WorkflowCatalogV1.get("workflow.information.bad_weather")?.name)
        assertEquals(BusinessDomainId.INFORMATION_SERVICE, WorkflowCatalogV1.get("workflow.information.bad_weather")?.ownerDomainId)
        assertEquals(null, WorkflowCatalogV1.get("workflow.not.exist"))
    }
}
