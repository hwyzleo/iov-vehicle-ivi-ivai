package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-009 验证设计 · Governance Validator（11 步发布门禁）。
 *
 * 覆盖 TC-GOV-001～TC-GOV-020 的代表性阻断条件：
 *  - 基线目录全量通过校验（10 Domain / 160 Tool / 18 Workflow，允许区间内）。
 *  - Tool 超出允许区间 → BASELINE_OUT_OF_RANGE。
 *  - Domain 数量不为 10 → DOMAIN_BASELINE_INVALID。
 *  - Workflow 引用未知 Tool → TOOL_REFERENCE_INVALID。
 *  - Pack 引用缺失 → PACK_REFERENCE_INVALID。
 *  - 重复 Tool ID → DUPLICATE_ID。
 *  - NEEDS_REVIEW 项 → GOV_STATUS_REJECTED（阻止发布）。
 */
class GovernanceValidatorTest {

    private fun baseline() = GovernanceWorkspace.baseline

    @Test
    fun `基线目录全部通过发布门禁`() {
        val result = GovernanceWorkspace.validation
        assertTrue(result.passed, "门禁失败: ${result.failedChecks}")
        assertEquals(11, result.checks.size)
        assertTrue(result.checks.all { it.passed })
    }

    @Test
    fun `Tool 数量超出允许区间被阻止`() {
        val outOfRangeTools = ToolCatalogV1.ALL + ToolCatalogV1.ALL.take(60) // 220 > 190
        val result = GovernanceValidator(
            baseline(), CapabilityPackCatalogV1.ALL, outOfRangeTools, WorkflowCatalogV1.ALL
        ).validate()
        assertFalse(result.passed)
        val check = result.failedChecks.first { it.step == "Count and range validation" }
        assertEquals(GovernanceReasonCode.DOMAIN_BASELINE_INVALID, check.reasonCode)
        assertTrue(check.detail!!.contains("toolCount=220"))
    }

    @Test
    fun `Domain 数量不为 10 被阻止`() {
        // 只取 9 个领域 → domainCount < 10。
        val nineDomains = ToolCatalogV1.ALL.filter {
            it.domainId != BusinessDomainId.INFORMATION_SERVICE
        }
        val result = GovernanceValidator(
            baseline(), CapabilityPackCatalogV1.ALL, nineDomains, WorkflowCatalogV1.ALL
        ).validate()
        assertFalse(result.passed)
        val check = result.failedChecks.first { it.step == "Count and range validation" }
        assertTrue(check.detail!!.contains("domainCount=9"))
    }

    @Test
    fun `Workflow 引用未知 Tool 被阻止`() {
        val badWorkflow = WorkflowGovernanceSpec(
            workflowId = "workflow.test.bad_ref",
            name = "坏引用",
            ownerDomainId = BusinessDomainId.CABIN_COMFORT,
            domainIds = setOf(BusinessDomainId.CABIN_COMFORT),
            triggerParams = "x",
            stepToolIds = listOf("not.a.real.tool"),
            failurePolicy = "abort",
            priority = ImplementationPriority.P0
        )
        val result = GovernanceValidator(
            baseline(), CapabilityPackCatalogV1.ALL, ToolCatalogV1.ALL, WorkflowCatalogV1.ALL + badWorkflow
        ).validate()
        assertFalse(result.passed)
        val check = result.failedChecks.first { it.step == "Workflow graph validation" }
        assertEquals(GovernanceReasonCode.TOOL_REFERENCE_INVALID, check.reasonCode)
    }

    @Test
    fun `Pack 引用缺失被阻止`() {
        val badTool = ToolCatalogV1.ALL.first().copy(
            toolId = "tool.with.missing.pack",
            capabilityPackId = "no.such.pack"
        )
        val result = GovernanceValidator(
            baseline(), CapabilityPackCatalogV1.ALL, ToolCatalogV1.ALL + badTool, WorkflowCatalogV1.ALL
        ).validate()
        assertFalse(result.passed)
        val check = result.failedChecks.first { it.step == "Capability Pack reference validation" }
        assertEquals(GovernanceReasonCode.PACK_REFERENCE_INVALID, check.reasonCode)
    }

    @Test
    fun `重复 Tool ID 被阻止`() {
        val dupTool = ToolCatalogV1.ALL.first().copy(toolId = ToolCatalogV1.ALL.first().toolId)
        val result = GovernanceValidator(
            baseline(), CapabilityPackCatalogV1.ALL, ToolCatalogV1.ALL + dupTool, WorkflowCatalogV1.ALL
        ).validate()
        assertFalse(result.passed)
        val check = result.failedChecks.first { it.step == "Alias orphan/duplicate validation" }
        assertEquals(GovernanceReasonCode.DUPLICATE_ID, check.reasonCode)
    }

    @Test
    fun `NEEDS_REVIEW 资产进入索引被阻止`() {
        val needsReviewTool = ToolCatalogV1.ALL.first().copy(
            toolId = "tool.needs.review",
            status = GovernanceStatus.NEEDS_REVIEW
        )
        val result = GovernanceValidator(
            baseline(), CapabilityPackCatalogV1.ALL, ToolCatalogV1.ALL + needsReviewTool, WorkflowCatalogV1.ALL
        ).validate()
        assertFalse(result.passed)
        val check = result.failedChecks.first { it.step == "Atomic publish" }
        assertEquals(GovernanceReasonCode.GOV_STATUS_REJECTED, check.reasonCode)
    }

    @Test
    fun `来源目录缺失被阻止`() {
        val emptySource = SourceCatalogStats(
            domainCount = 0, standardFeatureCount = 0, instructionCount = 0,
            mappedFeatureCount = 0, needsReviewFeatureCount = 0
        )
        val result = GovernanceValidator(
            baseline(), CapabilityPackCatalogV1.ALL, ToolCatalogV1.ALL, WorkflowCatalogV1.ALL, emptySource
        ).validate()
        assertFalse(result.passed)
        val check = result.failedChecks.first { it.step == "Source completeness" }
        assertEquals(GovernanceReasonCode.MAPPING_COVERAGE_LOW, check.reasonCode)
    }
}
