package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-009 + CR-010 验证设计 · Contract Test Catalog v1：
 *  - 总量 1,500 = 50 Domain + 1280 Tool + 144 Workflow + 26 Governance。
 *  - 各层数量与公式一致（10×5 / 160×8 / 18×8 / 26）。
 *  - 每条可追溯（Test ID、对象、reasonCode）。
 *  - 代表性 reasonCode（TC-GOV-001～026、Domain 路由、Workflow 成功）。
 */
class ContractTestCatalogTest {

    @Test
    fun `契约测试总量为 1500`() {
        assertEquals(1500, ContractTestCatalog.ALL.size)
        assertEquals(1500, ContractTestCatalog.expectedTotal)
    }

    @Test
    fun `分层数量与公式一致`() {
        val counts = ContractTestCatalog.layerCounts()
        assertEquals(50, counts[ContractTestType.DOMAIN])      // 10 × 5
        assertEquals(1280, counts[ContractTestType.TOOL])      // 160 × 8（CR-010 增 DET_L0 / DET_AMBIGUOUS）
        assertEquals(144, counts[ContractTestType.WORKFLOW])   // 18 × 8
        assertEquals(26, counts[ContractTestType.GOVERNANCE])  // 20 + CR-010 6
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
    fun `Governance 测试覆盖 TC-GOV-001 至 TC-GOV-026`() {
        val gov = ContractTestCatalog.governanceTests
        assertEquals(26, gov.size)
        assertEquals("TC-GOV-001", gov.first().testId)
        assertEquals("TC-GOV-026", gov.last().testId)
        assertEquals("GOV_STATUS_REJECTED", gov[0].expectedReasonCode)
        assertEquals("AUDIT_TRACE_MISSING", gov[19].expectedReasonCode)
        // CR-010 新错误码（TC-GOV-021～026）。
        assertEquals("IVAI-CAP-003", gov[20].expectedReasonCode)
        assertEquals("IVAI-ROUTE-003", gov[21].expectedReasonCode)
        assertEquals("IVAI-ROUTE-004", gov[22].expectedReasonCode)
        assertEquals("IVAI-GOV-003", gov[23].expectedReasonCode)
        assertEquals("IVAI-GOV-004", gov[24].expectedReasonCode)
        assertEquals("IVAI-ALIAS-001", gov[25].expectedReasonCode)
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
    fun `Tool 测试覆盖 160 个 Tool 共 1280 条含确定性覆盖分类`() {
        val toolTests = ContractTestCatalog.toolTests
        assertEquals(1280, toolTests.size)
        val perTool = toolTests.groupingBy { it.objectId }.eachCount()
        assertEquals(160, perTool.size)
        assertTrue(perTool.values.all { it == 8 }, "每个 Tool 应有 8 类")
        // CR-010：具备确定性画像的 Tool → DET_L0 唯一匹配走 L0；否则记录覆盖缺口。
        val detL0 = toolTests.filter { it.category == "DET_L0" }
        assertEquals(160, detL0.size)
        val profiled = detL0.first { it.objectId == "climate.power.set" }
        assertEquals("L0_UNIQUE_MATCH", profiled.expectedReasonCode)
        val unprofiled = detL0.first { it.objectId == "body.window.set" }
        assertEquals("DETERMINISTIC_COVERAGE_MISSING", unprofiled.expectedReasonCode)
        val detAmb = toolTests.first { it.category == "DET_AMBIGUOUS" && it.objectId == "climate.power.set" }
        assertEquals("IVAI-ROUTE-003", detAmb.expectedReasonCode)
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
