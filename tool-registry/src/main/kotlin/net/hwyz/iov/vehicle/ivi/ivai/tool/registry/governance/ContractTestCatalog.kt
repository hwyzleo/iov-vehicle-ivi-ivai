package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType

/** 契约测试类型。 */
enum class ContractTestType {
    DOMAIN,      // 领域/路由/OperationType/歧义/否定/跨领域
    TOOL,        // 正向/Alias/缺参/边界/Policy/执行保护
    WORKFLOW,    // 成功/缺参/不可用/取消/失败/补偿/幂等/恢复
    GOVERNANCE   // 状态/Binding/Hash/引用/Schema/数量/覆盖率/评测/回滚
}

/**
 * 一条可追溯契约测试（IVI-IVAI-DSN-CR-009 Contract Test Catalog v1）。
 * 每条结果必须关联 domainId / toolId / workflowId、sourceFeature、Function-ID、
 * governanceVersion 和预期 reasonCode。
 */
data class ContractTestSpec(
    val testId: String,
    val type: ContractTestType,
    val objectId: String,
    val category: String,
    val expectedReasonCode: String,
    val priority: ImplementationPriority,
    val governanceVersion: String = "ivai-governance-v1-draft"
)

/**
 * IVAI Contract Test Catalog v1（IVI-IVAI-DSN-CR-009 规范性附录）。
 *
 * 当前包含 1,174 条测试：
 *  - Domain：50 = 10 个业务领域 × 路由、OperationType、歧义、否定和跨领域 5 类
 *  - Tool：960 = 160 Tool × 正向、Alias、缺参、边界、Policy/Binding、执行保护 6 类
 *  - Workflow：144 = 18 Workflow × 成功、缺参、不可用、取消、失败、补偿、幂等、恢复 8 类
 *  - Governance：20 = 状态、Binding、Hash/签名、引用、Schema、循环依赖、数量、
 *    覆盖率、评测和回滚 10 类 × 2
 *
 * 测试允许通过数据驱动方式生成，但每条结果必须可追溯。
 */
object ContractTestCatalog {

    private const val DOMAIN_CATEGORIES = 5
    private const val TOOL_CATEGORIES = 6
    private const val WORKFLOW_CATEGORIES = 8
    private const val GOVERNANCE_TESTS = 20

    val domainTests: List<ContractTestSpec> by lazy {
        val domains = BusinessDomainId.entries
        val categories = listOf(
            "ROUTE", "OPERATION", "AMBIGUITY", "NEGATION", "CROSS"
        )
        domains.flatMap { domain ->
            categories.mapIndexed { index, category ->
                ContractTestSpec(
                    testId = "TC-${domain.code}-$category",
                    type = ContractTestType.DOMAIN,
                    objectId = domain.code,
                    category = category,
                    expectedReasonCode = expectedDomainReason(category),
                    priority = ImplementationPriority.P0
                )
            }
        }
    }

    val toolTests: List<ContractTestSpec> by lazy {
        val categories = listOf(
            "POS", "ALIAS", "MISSING", "BOUNDARY", "POLICY", "EXEC"
        )
        ToolCatalogV1.ALL.flatMap { tool ->
            categories.map { category ->
                ContractTestSpec(
                    testId = "TC-${tool.toolId}-$category",
                    type = ContractTestType.TOOL,
                    objectId = tool.toolId,
                    category = category,
                    expectedReasonCode = expectedToolReason(tool, category),
                    priority = tool.priority
                )
            }
        }
    }

    val workflowTests: List<ContractTestSpec> by lazy {
        val categories = listOf(
            "POS", "MISSING", "UNAVAILABLE", "CANCEL", "FAIL", "COMP", "IDEM", "RECOVER"
        )
        WorkflowCatalogV1.ALL.flatMap { wf ->
            categories.map { category ->
                ContractTestSpec(
                    testId = "TC-${wf.workflowId}-$category",
                    type = ContractTestType.WORKFLOW,
                    objectId = wf.workflowId,
                    category = category,
                    expectedReasonCode = expectedWorkflowReason(category),
                    priority = wf.priority
                )
            }
        }
    }

    val governanceTests: List<ContractTestSpec> by lazy {
        val codes = listOf(
            GovernanceReasonCode.GOV_STATUS_REJECTED,     // 001
            GovernanceReasonCode.BINDING_MISSING,         // 002
            GovernanceReasonCode.MANIFEST_INVALID,        // 003
            GovernanceReasonCode.WORKFLOW_CYCLE,          // 004
            GovernanceReasonCode.BASELINE_OUT_OF_RANGE,   // 005
            GovernanceReasonCode.DUPLICATE_ID,            // 006
            GovernanceReasonCode.ORPHAN_ALIAS,            // 007
            GovernanceReasonCode.DOMAIN_BASELINE_INVALID, // 008
            GovernanceReasonCode.GOV_VERSION_MISMATCH,    // 009
            GovernanceReasonCode.SIGNATURE_INVALID,       // 010
            GovernanceReasonCode.PACK_REFERENCE_INVALID,  // 011
            GovernanceReasonCode.TOOL_REFERENCE_INVALID,  // 012
            GovernanceReasonCode.TOOL_SCHEMA_INVALID,     // 013
            GovernanceReasonCode.WORKFLOW_STEP_LIMIT,     // 014
            GovernanceReasonCode.BINDING_CONFLICT,        // 015
            GovernanceReasonCode.ALIAS_CONFLICT,          // 016
            GovernanceReasonCode.MAPPING_COVERAGE_LOW,    // 017
            GovernanceReasonCode.EVALUATION_GATE_FAILED,  // 018
            GovernanceReasonCode.GOVERNANCE_ROLLBACK,     // 019
            GovernanceReasonCode.AUDIT_TRACE_MISSING      // 020
        )
        codes.mapIndexed { index, code ->
            ContractTestSpec(
                testId = "TC-GOV-${(index + 1).toString().padStart(3, '0')}",
                type = ContractTestType.GOVERNANCE,
                objectId = "GovernanceManifest",
                category = "GOV-${(index + 1).toString().padStart(3, '0')}",
                expectedReasonCode = code,
                priority = ImplementationPriority.P0
            )
        }
    }

    val ALL: List<ContractTestSpec> by lazy {
        domainTests + toolTests + workflowTests + governanceTests
    }

    const val expectedTotal: Int = 1174

    /** 分层统计（50 / 960 / 144 / 20）。 */
    fun layerCounts(): Map<ContractTestType, Int> = mapOf(
        ContractTestType.DOMAIN to domainTests.size,
        ContractTestType.TOOL to toolTests.size,
        ContractTestType.WORKFLOW to workflowTests.size,
        ContractTestType.GOVERNANCE to governanceTests.size
    )

    private fun expectedDomainReason(category: String): String = when (category) {
        "ROUTE" -> "DOMAIN_ROUTED"
        "OPERATION" -> "OPERATION_CLASSIFIED"
        "AMBIGUITY" -> "DOMAIN_AMBIGUOUS"
        "NEGATION" -> "NEGATION_GUARDED"
        "CROSS" -> "MULTI_DOMAIN_CONTROLLED"
        else -> "UNKNOWN"
    }

    private fun expectedToolReason(tool: ToolGovernanceSpec, category: String): String = when (category) {
        "POS" -> "SUCCEEDED"
        "ALIAS" -> "ALIAS_RESOLVED"
        "MISSING" -> "MISSING_ARGUMENT"
        "BOUNDARY" -> "OUT_OF_RANGE"
        "POLICY" -> if (tool.policySummary.startsWith("HIGH")) "NEEDS_CONFIRMATION" else "POLICY_ALLOWED"
        "EXEC" -> "IVAI-EXEC-001"
        else -> "UNKNOWN"
    }

    private fun expectedWorkflowReason(category: String): String = when (category) {
        "POS" -> "WORKFLOW_SUCCEEDED"
        "MISSING" -> "MISSING_ARGUMENT"
        "UNAVAILABLE" -> "STEP_UNAVAILABLE"
        "CANCEL" -> "WORKFLOW_CANCELLED"
        "FAIL" -> "STEP_FAILED"
        "COMP" -> "COMPENSATED"
        "IDEM" -> "IDEMPOTENT"
        "RECOVER" -> "WORKFLOW_RECOVERED"
        else -> "UNKNOWN"
    }
}
