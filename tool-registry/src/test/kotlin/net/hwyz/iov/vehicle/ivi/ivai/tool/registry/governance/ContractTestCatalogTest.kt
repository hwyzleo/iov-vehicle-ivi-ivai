package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-009 + CR-010 + CR-011 + CR-013 验证设计 · Contract Test Catalog v1：
 *  - 总量 1,991 = 50 Domain + 1760 Tool + 144 Workflow + 37 Governance。
 *  - 各层数量与公式一致（10×5 / 160×11 / 18×8 / 37）。
 *  - 每条可追溯（Test ID、对象、reasonCode）。
 *  - 代表性 reasonCode（TC-GOV-001～037、Domain 路由、Workflow 成功）。
 *  - CR-013：SUPPORTED → L0；NOT_SUPPORTED/NEEDS_REVIEW → L1 合法候选（非缺口）。
 */
class ContractTestCatalogTest {

    @Test
    fun `契约测试总量为 1991`() {
        assertEquals(1991, ContractTestCatalog.ALL.size)
        assertEquals(1991, ContractTestCatalog.expectedTotal)
    }

    @Test
    fun `分层数量与公式一致`() {
        val counts = ContractTestCatalog.layerCounts()
        assertEquals(50, counts[ContractTestType.DOMAIN])      // 10 × 5
        assertEquals(1760, counts[ContractTestType.TOOL])      // 160 × 11（CR-010 增 DET_L0/DET_AMBIGUOUS；CR-013 增 DET_CONFLICT/DET_ARG/DET_DEGRADE）
        assertEquals(144, counts[ContractTestType.WORKFLOW])   // 18 × 8
        assertEquals(37, counts[ContractTestType.GOVERNANCE])  // 20 + CR-010 6 + CR-011 6 + CR-013 5
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
    fun `Governance 测试覆盖 TC-GOV-001 至 TC-GOV-037`() {
        val gov = ContractTestCatalog.governanceTests
        assertEquals(37, gov.size)
        assertEquals("TC-GOV-001", gov.first().testId)
        assertEquals("TC-GOV-037", gov.last().testId)
        assertEquals("GOV_STATUS_REJECTED", gov[0].expectedReasonCode)
        assertEquals("AUDIT_TRACE_MISSING", gov[19].expectedReasonCode)
        // CR-010 新错误码（TC-GOV-021～026）。
        assertEquals("IVAI-CAP-003", gov[20].expectedReasonCode)
        assertEquals("IVAI-ROUTE-003", gov[21].expectedReasonCode)
        assertEquals("IVAI-ROUTE-004", gov[22].expectedReasonCode)
        assertEquals("IVAI-GOV-003", gov[23].expectedReasonCode)
        assertEquals("IVAI-GOV-004", gov[24].expectedReasonCode)
        assertEquals("IVAI-ALIAS-001", gov[25].expectedReasonCode)
        // CR-011 RAG 错误码（TC-GOV-027～032）。
        assertEquals("IVAI-RAG-001", gov[26].expectedReasonCode)
        assertEquals("IVAI-RAG-002", gov[27].expectedReasonCode)
        assertEquals("IVAI-RAG-003", gov[28].expectedReasonCode)
        assertEquals("IVAI-RAG-004", gov[29].expectedReasonCode)
        assertEquals("IVAI-RAG-005", gov[30].expectedReasonCode)
        assertEquals("IVAI-RAG-006", gov[31].expectedReasonCode)
        // CR-013 L0 治理（TC-GOV-033～037）。
        assertEquals("IVAI-GOV-005", gov[32].expectedReasonCode)   // L0 资格/版本/Hash 不一致
        assertEquals("IVAI-GOV-005", gov[33].expectedReasonCode)   // 规则编译失败
        assertEquals("IVAI-ROUTE-003", gov[34].expectedReasonCode) // 跨 Tool 冲突
        assertEquals("IVAI-ROUTE-005", gov[35].expectedReasonCode) // 参数优先级/矛盾
        assertEquals("IVAI-GOV-006", gov[36].expectedReasonCode)   // 降级类型（非法入 Matcher）
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
    fun `Tool 测试覆盖 160 个 Tool 共 1760 条含 L0 资格分类`() {
        val toolTests = ContractTestCatalog.toolTests
        assertEquals(1760, toolTests.size)
        val perTool = toolTests.groupingBy { it.objectId }.eachCount()
        assertEquals(160, perTool.size)
        assertTrue(perTool.values.all { it == 11 }, "每个 Tool 应有 11 类")
        // CR-010/CR-013：DET_L0 唯一匹配走 L0（SUPPORTED）；否则 L1 合法候选（非缺口）。
        val detL0 = toolTests.filter { it.category == "DET_L0" }
        assertEquals(160, detL0.size)
        assertEquals("L0_UNIQUE_MATCH", detL0.first { it.objectId == "climate.power.set" }.expectedReasonCode)
        assertEquals("L0_UNIQUE_MATCH", detL0.first { it.objectId == "body.window.set" }.expectedReasonCode)
        // NOT_SUPPORTED Tool 正常进入 L1，不记录 DETERMINISTIC_COVERAGE_MISSING。
        assertEquals("L1_LEGAL_CANDIDATE", detL0.first { it.objectId == "media.playback.play" }.expectedReasonCode)
        assertEquals("L1_LEGAL_CANDIDATE", detL0.first { it.objectId == "vehicle.drive_mode.set" }.expectedReasonCode)
        assertEquals("IVAI-ROUTE-003", toolTests.first { it.category == "DET_AMBIGUOUS" && it.objectId == "climate.power.set" }.expectedReasonCode)
        // CR-013：跨 Tool 冲突（有冲突集 → ROUTE-003）。
        assertEquals("IVAI-ROUTE-003", toolTests.first { it.category == "DET_CONFLICT" && it.objectId == "body.window.set" }.expectedReasonCode)
        // CR-018：power.set 已纳入 auto/vent/fan/airflow 冲突集 → ROUTE-003（原为空 → SINGLETON）。
        assertEquals("IVAI-ROUTE-003", toolTests.first { it.category == "DET_CONFLICT" && it.objectId == "climate.power.set" }.expectedReasonCode)
        // CR-013：参数矛盾检测（SUPPORTED → ROUTE-005）。
        assertEquals("IVAI-ROUTE-005", toolTests.first { it.category == "DET_ARG" && it.objectId == "climate.power.set" }.expectedReasonCode)
        // CR-013：降级类型。
        assertEquals("L0_ENABLED", toolTests.first { it.category == "DET_DEGRADE" && it.objectId == "climate.power.set" }.expectedReasonCode)
        assertEquals("L1_LEGAL_CANDIDATE", toolTests.first { it.category == "DET_DEGRADE" && it.objectId == "media.playback.play" }.expectedReasonCode)
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
