package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-009 验证设计 · Contract Test Catalog v1：
 *  - 总量 1,174 = 50 Domain + 960 Tool + 144 Workflow + 20 Governance。
 *  - 各层数量与公式一致（10×5 / 160×6 / 18×8 / 20）。
 *  - 每条可追溯（Test ID、对象、reasonCode）。
 *  - 代表性 reasonCode（TC-GOV-001～020、Domain 路由、Workflow 成功）。
 */
class ContractTestCatalogTest {

    @Test
    fun `契约测试总量为 1174`() {
        assertEquals(1174, ContractTestCatalog.ALL.size)
        assertEquals(1174, ContractTestCatalog.expectedTotal)
    }

    @Test
    fun `分层数量与公式一致`() {
        val counts = ContractTestCatalog.layerCounts()
        assertEquals(50, counts[ContractTestType.DOMAIN])      // 10 × 5
        assertEquals(960, counts[ContractTestType.TOOL])       // 160 × 6
        assertEquals(144, counts[ContractTestType.WORKFLOW])   // 18 × 8
        assertEquals(20, counts[ContractTestType.GOVERNANCE])  // 20
    }

    @Test
    fun `每条测试可追溯且 reasonCode 非空`() {
        for (spec in ContractTestCatalog.ALL) {
            assertTrue(spec.testId.isNotBlank(), "Test ID 非空")
            assertTrue(spec.objectId.isNotBlank(), "对象 ID 非空")
            assertTrue(spec.expectedReasonCode.isNotBlank(), "reasonCode 非空")
        }
    }

    @Test
    fun `Governance 测试覆盖 TC-GOV-001 至 TC-GOV-020`() {
        val gov = ContractTestCatalog.governanceTests
        assertEquals(20, gov.size)
        assertEquals("TC-GOV-001", gov.first().testId)
        assertEquals("TC-GOV-020", gov.last().testId)
        assertEquals("GOV_STATUS_REJECTED", gov[0].expectedReasonCode)
        assertEquals("AUDIT_TRACE_MISSING", gov[19].expectedReasonCode)
    }

    @Test
    fun `Domain 测试覆盖 10 个领域共 50 条`() {
        val domains = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId.entries
        val domainTests = ContractTestCatalog.domainTests
        assertEquals(50, domainTests.size)
        // 每个领域 5 类。
        for (domain in domains) {
            val perDomain = domainTests.filter { it.objectId == domain.code }
            assertEquals(5, perDomain.size, "Domain ${domain.code} 应有 5 条")
        }
    }

    @Test
    fun `Tool 测试覆盖 160 个 Tool 共 960 条`() {
        val toolTests = ContractTestCatalog.toolTests
        assertEquals(960, toolTests.size)
        val perTool = toolTests.groupingBy { it.objectId }.eachCount()
        assertEquals(160, perTool.size)
        assertTrue(perTool.values.all { it == 6 }, "每个 Tool 应有 6 类")
    }

    @Test
    fun `Workflow 测试覆盖 18 个 Workflow 共 144 条`() {
        val wfTests = ContractTestCatalog.workflowTests
        assertEquals(144, wfTests.size)
        val perWf = wfTests.groupingBy { it.objectId }.eachCount()
        assertEquals(18, perWf.size)
        assertTrue(perWf.values.all { it == 8 }, "每个 Workflow 应有 8 类")
    }

    @Test
    fun `代表性 reasonCode 正确`() {
        val domainRoute = ContractTestCatalog.domainTests.first { it.category == "ROUTE" }
        assertEquals("DOMAIN_ROUTED", domainRoute.expectedReasonCode)
        val wfPos = ContractTestCatalog.workflowTests.first { it.category == "POS" }
        assertEquals("WORKFLOW_SUCCEEDED", wfPos.expectedReasonCode)
        val wfRecover = ContractTestCatalog.workflowTests.first { it.category == "RECOVER" }
        assertEquals("WORKFLOW_RECOVERED", wfRecover.expectedReasonCode)
    }
}
