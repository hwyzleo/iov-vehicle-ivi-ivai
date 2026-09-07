package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicSupport
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
 * IVAI Contract Test Catalog v1（IVI-IVAI-DSN-CR-009 + CR-010 + CR-013 规范性附录）。
 *
 * 当前包含 1,991 条测试：
 *  - Domain：50 = 10 个业务领域 × 路由、OperationType、歧义、否定和跨领域 5 类
 *  - Tool：1760 = 160 Tool × 正向、Alias、缺参、边界、Policy/Binding、执行保护、
 *    确定性直达（DET_L0）、确定性歧义（DET_AMBIGUOUS）8 类
 *    + CR-013 确定性冲突（DET_CONFLICT）、参数优先级/矛盾（DET_ARG）、
 *    降级类型（DET_DEGRADE）3 类
 *  - Workflow：144 = 18 Workflow × 成功、缺参、不可用、取消、失败、补偿、幂等、恢复 8 类
 *  - Governance：37 = 状态、Binding、Hash/签名、引用、Schema、循环依赖、数量、
 *    覆盖率、评测、回滚 10 类 × 2 + CR-010 新错误码 6 类（TC-GOV-021～026）
 *    + CR-011 RAG 错误码 6 类（TC-GOV-027～032）
 *    + CR-013 L0 资格 / 规则编译 / 跨 Tool 冲突 / 参数优先级 / 降级类型 5 类
 *    （TC-GOV-033～037）
 *
 * 测试允许通过数据驱动方式生成，但每条结果必须可追溯。
 */
object ContractTestCatalog {

    private const val DOMAIN_CATEGORIES = 5
    private const val TOOL_CATEGORIES = 11
    private const val WORKFLOW_CATEGORIES = 8
    private const val GOVERNANCE_TESTS = 37

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
            "POS", "ALIAS", "MISSING", "BOUNDARY", "POLICY", "EXEC", "DET_L0", "DET_AMBIGUOUS",
            // CR-013：跨 Tool/Workflow 确定性冲突 / 参数优先级与矛盾 / 降级类型。
            "DET_CONFLICT", "DET_ARG", "DET_DEGRADE"
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
            GovernanceReasonCode.AUDIT_TRACE_MISSING,     // 020
            // CR-010 新错误码：canonical 候选引用不闭合 / 确定性冲突 / 覆盖缺口 /
            // DRAFT 非法提升 / 开发桩豁免非法 / 旧 ID 冲突映射
            Cr010ErrorCodes.CAP_CANONICAL_UNCLOSED,        // 021 (IVAI-CAP-003)
            Cr010ErrorCodes.ROUTE_CONFLICT,                // 022 (IVAI-ROUTE-003)
            Cr010ErrorCodes.ROUTE_COVERAGE_MISSING,        // 023 (IVAI-ROUTE-004)
            Cr010ErrorCodes.GOV_DRAFT_PROMOTED,            // 024 (IVAI-GOV-003)
            Cr010ErrorCodes.GOV_STUB_EXEMPTION_INVALID,    // 025 (IVAI-GOV-004)
            Cr010ErrorCodes.ALIAS_CONFLICT,                 // 026 (IVAI-ALIAS-001)
            // CR-011 RAG 错误码（定义于 retrieval.RagErrorCode；目录以字面量登记避免循环依赖）：
            // 无索引 / Embedding 不可用 / 向量非法 / Manifest 不兼容 / 完整性失败 / 无候选达阈值
            "IVAI-RAG-001",                                // 027
            "IVAI-RAG-002",                                // 028
            "IVAI-RAG-003",                                // 029
            "IVAI-RAG-004",                                // 030
            "IVAI-RAG-005",                                // 031
            "IVAI-RAG-006",                                // 032
            // CR-013 L0 治理：L0资格 / 规则编译 / 跨 Tool 冲突 / 参数优先级 / 降级类型
            Cr013ErrorCodes.GOV_L0_INCONSISTENT,            // 033 (IVAI-GOV-005)
            Cr013ErrorCodes.GOV_L0_INCONSISTENT,            // 034 (IVAI-GOV-005 规则编译失败)
            Cr013ErrorCodes.ROUTE_CONFLICT,                 // 035 (IVAI-ROUTE-003 跨 Tool 冲突)
            Cr013ErrorCodes.ROUTE_ARGUMENT_CONFLICT,        // 036 (IVAI-ROUTE-005 参数矛盾)
            Cr013ErrorCodes.GOV_L0_ILLEGAL_MATCHER          // 037 (IVAI-GOV-006 降级类型)
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

    const val expectedTotal: Int = 1991

    /** 分层统计（50 / 1760 / 144 / 37）。 */
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
        // CR-010/CR-013：DET_L0 唯一匹配走 L0（SUPPORTED 且规则完整）；NOT_SUPPORTED /
        // NEEDS_REVIEW 正常进入 L1，不记录 DETERMINISTIC_COVERAGE_MISSING 缺口。
        "DET_L0" -> l0Support(tool.toolId).let { support ->
            when (support) {
                DeterministicSupport.SUPPORTED -> "L0_UNIQUE_MATCH"
                DeterministicSupport.NOT_SUPPORTED -> "L1_LEGAL_CANDIDATE"
                DeterministicSupport.NEEDS_REVIEW -> "L1_LEGAL_CANDIDATE"
            }
        }
        // CR-010：确定性匹配冲突，不能唯一确定 Tool → IVAI-ROUTE-003。
        "DET_AMBIGUOUS" -> Cr010ErrorCodes.ROUTE_CONFLICT
        // CR-013：跨 Tool/Workflow 冲突判定（有冲突集 → 可能产生 DETERMINISTIC_CONFLICT）。
        "DET_CONFLICT" -> if (l0ConflictSet(tool.toolId).isNotEmpty()) {
            Cr013ErrorCodes.ROUTE_CONFLICT
        } else {
            "DETERMINISTIC_SINGLETON"
        }
        // CR-013：参数合并优先级与矛盾检测（显式槽位 vs 预置 → IVAI-ROUTE-005）。
        "DET_ARG" -> if (l0Support(tool.toolId) == DeterministicSupport.SUPPORTED) {
            Cr013ErrorCodes.ROUTE_ARGUMENT_CONFLICT
        } else {
            "NOT_L0_CANDIDATE"
        }
        // CR-013：降级类型（NOT_SUPPORTED/NEEDS_REVIEW 保留 L1，非法入 Matcher → GOV-006）。
        "DET_DEGRADE" -> when (l0Support(tool.toolId)) {
            DeterministicSupport.SUPPORTED -> "L0_ENABLED"
            DeterministicSupport.NOT_SUPPORTED -> "L1_LEGAL_CANDIDATE"
            DeterministicSupport.NEEDS_REVIEW -> "L1_LEGAL_CANDIDATE"
        }
        else -> "UNKNOWN"
    }

    /** Lazy 获取确定性目录（避免循环依赖 / 重复构建）。 */
    private val l0Catalog: DeterministicIntentCatalog by lazy { DeterministicIntentCatalog.build() }

    private fun l0Support(toolId: String): DeterministicSupport =
        l0Catalog.profileFor(toolId)?.support ?: DeterministicSupport.NOT_SUPPORTED

    private fun l0ConflictSet(toolId: String): Set<String> =
        l0Catalog.profileFor(toolId)?.conflictToolIds ?: emptySet()

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
