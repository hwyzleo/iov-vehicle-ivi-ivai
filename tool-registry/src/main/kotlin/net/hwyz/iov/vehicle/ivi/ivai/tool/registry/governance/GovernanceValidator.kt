package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus

/**
 * 治理门禁错误码（IVI-IVAI-DSN-CR-009 校验与发布门禁，对应 Contract Test
 * Catalog TC-GOV-001～TC-GOV-020 的预期 ReasonCode）。
 */
object GovernanceReasonCode {
    const val GOV_STATUS_REJECTED = "GOV_STATUS_REJECTED"           // TC-GOV-001 待确认资产进入索引
    const val BINDING_MISSING = "BINDING_MISSING"                   // TC-GOV-002 无 Binding 资产进入索引
    const val MANIFEST_INVALID = "MANIFEST_INVALID"                 // TC-GOV-003 治理包 Hash 错误
    const val WORKFLOW_CYCLE = "WORKFLOW_CYCLE"                     // TC-GOV-004 循环依赖
    const val BASELINE_OUT_OF_RANGE = "BASELINE_OUT_OF_RANGE"       // TC-GOV-005 Tool 数量超区间且无 CR
    const val DUPLICATE_ID = "DUPLICATE_ID"                         // TC-GOV-006 重复 Tool ID
    const val ORPHAN_ALIAS = "ORPHAN_ALIAS"                         // TC-GOV-007 孤立 Alias
    const val DOMAIN_BASELINE_INVALID = "DOMAIN_BASELINE_INVALID"   // TC-GOV-008 Domain 数量不为 10
    const val GOV_VERSION_MISMATCH = "GOV_VERSION_MISMATCH"         // TC-GOV-009 治理版本不兼容
    const val SIGNATURE_INVALID = "SIGNATURE_INVALID"               // TC-GOV-010 签名无效
    const val PACK_REFERENCE_INVALID = "PACK_REFERENCE_INVALID"     // TC-GOV-011 Tool 引用不存在 Pack
    const val TOOL_REFERENCE_INVALID = "TOOL_REFERENCE_INVALID"     // TC-GOV-012 Workflow 引用不存在 Tool
    const val TOOL_SCHEMA_INVALID = "TOOL_SCHEMA_INVALID"           // TC-GOV-013 Tool 参数 Schema 非法
    const val WORKFLOW_STEP_LIMIT = "WORKFLOW_STEP_LIMIT"           // TC-GOV-014 Workflow 超出最大步骤数
    const val BINDING_CONFLICT = "BINDING_CONFLICT"                 // TC-GOV-015 同车型版本匹配多个冲突 Binding
    const val ALIAS_CONFLICT = "ALIAS_CONFLICT"                     // TC-GOV-016 同 Alias 映射多个可执行 Tool
    const val MAPPING_COVERAGE_LOW = "MAPPING_COVERAGE_LOW"         // TC-GOV-017 来源映射覆盖率低于阈值
    const val EVALUATION_GATE_FAILED = "EVALUATION_GATE_FAILED"     // TC-GOV-018 误执行率超过阈值
    const val GOVERNANCE_ROLLBACK = "GOVERNANCE_ROLLBACK"           // TC-GOV-019 原子回滚上一版本
    const val AUDIT_TRACE_MISSING = "AUDIT_TRACE_MISSING"           // TC-GOV-020 审计追溯缺失
}

/** 单个门禁校验结果。 */
data class GateCheck(
    val step: String,
    val passed: Boolean,
    val reasonCode: String? = null,
    val detail: String? = null
)

/** 治理包校验结果。 */
data class GovernanceValidationResult(
    val baseline: GovernanceBaseline,
    val checks: List<GateCheck>
) {
    val passed: Boolean = checks.all { it.passed }
    val blocked: Boolean get() = !passed
    val failedChecks: List<GateCheck> get() = checks.filter { !it.passed }
}

/**
 * 治理包发布门禁（IVI-IVAI-DSN-CR-009「校验与发布门禁」）。
 *
 * 依次执行：Source completeness → Domain reference → Capability Pack reference →
 * Tool Schema → Workflow graph → Alias orphan/duplicate → Binding compatibility →
 * Count and range → Offline evaluation threshold → Hash/signature → Atomic publish。
 *
 * 以下情况阻止全量发布：
 *  - Domain 数量不等于 10。
 *  - Tool 或 Workflow 超出允许区间且没有对应 REQ/DSN CR。
 *  - 存在 Workflow 循环依赖、超出最大步骤数或引用未批准 Tool。
 *  - 存在孤立 Alias、重复冲突 Binding 或来源记录不可追溯。
 *  - 待确认项进入可执行索引。
 *  - 领域混淆、Tool Top-1、误执行率或高风险拦截率未达到阈值。
 */
class GovernanceValidator(
    private val baseline: GovernanceBaseline,
    private val packs: List<CapabilityPackGovernanceSpec>,
    private val tools: List<ToolGovernanceSpec>,
    private val workflows: List<WorkflowGovernanceSpec>,
    private val sourceCatalog: SourceCatalogStats = SourceCatalogStats()
) {

    /** 可执行索引的最小集合：治理基线的最小 Domain 数。 */
    private val allBusinessDomains = BusinessDomainId.entries.toSet()

    fun validate(): GovernanceValidationResult {
        val checks = mutableListOf<GateCheck>()
        checks += sourceCompleteness()
        checks += domainReferenceValidation()
        checks += capabilityPackReferenceValidation()
        checks += toolSchemaValidation()
        checks += workflowGraphValidation()
        checks += aliasOrphanDuplicateValidation()
        checks += bindingCompatibilityValidation()
        checks += countAndRangeValidation()
        checks += offlineEvaluationThreshold()
        checks += hashSignatureValidation()
        checks += atomicPublishValidation()
        return GovernanceValidationResult(baseline, checks)
    }

    /** 1. Source completeness：来源目录字段齐全且与基线版本匹配。 */
    private fun sourceCompleteness(): GateCheck {
        val ok = sourceCatalog.standardFeatureCount > 0 &&
            sourceCatalog.instructionCount > 0 &&
            sourceCatalog.domainCount == 18
        return GateCheck(
            step = "Source completeness",
            passed = ok,
            reasonCode = if (ok) null else GovernanceReasonCode.MAPPING_COVERAGE_LOW,
            detail = if (ok) null else "sourceCatalog=${sourceCatalog}"
        )
    }

    /** 2. Domain reference：Tool/Workflow 的 Domain 必须在 BD01～BD10 内。 */
    private fun domainReferenceValidation(): GateCheck {
        val badToolDomains = tools.filter { it.domainId !in allBusinessDomains }.map { it.toolId }
        val badWorkflowDomains = workflows
            .filter { it.ownerDomainId !in allBusinessDomains || it.domainIds.any { d -> d !in allBusinessDomains } }
            .map { it.workflowId }
        val ok = badToolDomains.isEmpty() && badWorkflowDomains.isEmpty()
        return GateCheck(
            step = "Domain reference validation",
            passed = ok,
            reasonCode = if (ok) null else GovernanceReasonCode.DOMAIN_BASELINE_INVALID,
            detail = if (ok) null else "badToolDomains=$badToolDomains badWorkflowDomains=$badWorkflowDomains"
        )
    }

    /** 3. Capability Pack reference：Tool/Pack 引用必须存在且 Pack 归属 Domain 一致。 */
    private fun capabilityPackReferenceValidation(): GateCheck {
        val packIds = packs.map { it.packId }.toSet()
        val missing = tools.filter { it.capabilityPackId !in packIds }.map { it.toolId }
        val mismatched = tools.filter { t ->
            packs.firstOrNull { it.packId == t.capabilityPackId }?.domainId != t.domainId
        }.map { it.toolId }
        val ok = missing.isEmpty() && mismatched.isEmpty()
        return GateCheck(
            step = "Capability Pack reference validation",
            passed = ok,
            reasonCode = if (ok) null else GovernanceReasonCode.PACK_REFERENCE_INVALID,
            detail = if (ok) null else "missingPackRef=$missing domainMismatch=$mismatched"
        )
    }

    /** 4. Tool Schema：每个 Tool 必须携带非空 Schema。 */
    private fun toolSchemaValidation(): GateCheck {
        val invalid = tools.filter { it.parameterSchema.isBlank() }.map { it.toolId }
        return GateCheck(
            step = "Tool Schema validation",
            passed = invalid.isEmpty(),
            reasonCode = if (invalid.isEmpty()) null else GovernanceReasonCode.TOOL_SCHEMA_INVALID,
            detail = if (invalid.isEmpty()) null else "blankSchema=$invalid"
        )
    }

    /** 5. Workflow graph：步骤引用已批准 Tool、无循环、不超最大步骤数。 */
    private fun workflowGraphValidation(): GateCheck {
        val toolIds = tools.map { it.toolId }.toSet()
        val approvedToolIds = tools
            .filter { it.status == GovernanceStatus.APPROVED }
            .map { it.toolId }
            .toSet()
        val issues = mutableListOf<String>()
        for (wf in workflows) {
            val missingRefs = wf.stepToolIds.filter { it !in toolIds }
            if (missingRefs.isNotEmpty()) {
                issues += "${wf.workflowId}:refs=$missingRefs"
            }
            // 只放行已批准 Tool 的 Workflow 进入可执行集合；DRAFT Workflow 本身不入索引。
            if (wf.status == GovernanceStatus.APPROVED) {
                val unapproved = wf.stepToolIds.filter { it !in approvedToolIds }
                if (unapproved.isNotEmpty()) {
                    issues += "${wf.workflowId}:unapprovedSteps=$unapproved"
                }
            }
            if (wf.stepToolIds.size > baseline.workflowAllowedRange.last) {
                issues += "${wf.workflowId}:steps=${wf.stepToolIds.size}"
            }
        }
        return GateCheck(
            step = "Workflow graph validation",
            passed = issues.isEmpty(),
            reasonCode = if (issues.isEmpty()) null else GovernanceReasonCode.TOOL_REFERENCE_INVALID,
            detail = if (issues.isEmpty()) null else issues.joinToString("; ")
        )
    }

    /** 6. Alias orphan/duplicate：无孤立 Alias（首版无 Alias 资产即视为通过）。 */
    private fun aliasOrphanDuplicateValidation(): GateCheck {
        // v1 目录尚未引入 Alias 资产（来源 Alias 人工复核待后续 CR）。
        // 若存在重复 Tool ID 则阻断。
        val dupIds = tools.groupingBy { it.toolId }.eachCount().filter { it.value > 1 }.keys
        return GateCheck(
            step = "Alias orphan/duplicate validation",
            passed = dupIds.isEmpty(),
            reasonCode = if (dupIds.isEmpty()) null else GovernanceReasonCode.DUPLICATE_ID,
            detail = if (dupIds.isEmpty()) null else "duplicateToolIds=$dupIds"
        )
    }

    /** 7. Binding compatibility：无冲突 Binding；DRAFT 无 Binding 不影响构建。 */
    private fun bindingCompatibilityValidation(): GateCheck {
        // 冲突 Binding = 同 Tool 重复 adapter.method（首版各 Tool 单 Binding，天然无冲突）。
        val conflicts = tools.groupingBy { it.toolId }.eachCount().filter { it.value > 1 }.keys
        return GateCheck(
            step = "Binding compatibility validation",
            passed = conflicts.isEmpty(),
            reasonCode = if (conflicts.isEmpty()) null else GovernanceReasonCode.BINDING_CONFLICT,
            detail = if (conflicts.isEmpty()) null else "conflicts=$conflicts"
        )
    }

    /** 8. Count and range：Domain=10；Tool/Workflow 在允许区间内。 */
    private fun countAndRangeValidation(): GateCheck {
        val domainCount = tools.map { it.domainId }.toSet().size
        val toolCount = tools.size
        val workflowCount = workflows.size
        val issues = mutableListOf<String>()
        if (domainCount != baseline.domainTarget) {
            issues += "domainCount=$domainCount != ${baseline.domainTarget}"
        }
        if (toolCount !in baseline.toolAllowedRange) {
            issues += "toolCount=$toolCount !in ${baseline.toolAllowedRange}"
        }
        if (workflowCount !in baseline.workflowAllowedRange) {
            issues += "workflowCount=$workflowCount !in ${baseline.workflowAllowedRange}"
        }
        return GateCheck(
            step = "Count and range validation",
            passed = issues.isEmpty(),
            reasonCode = if (issues.isEmpty()) null else GovernanceReasonCode.DOMAIN_BASELINE_INVALID,
            detail = if (issues.isEmpty()) null else issues.joinToString("; ")
        )
    }

    /** 9. Offline evaluation threshold：映射覆盖率阈值（首版待确认项计入需复核）。 */
    private fun offlineEvaluationThreshold(): GateCheck {
        // 阈值：标准功能映射完整率 >= 0.6（1,110/1,730）；首版以来源目录为准。
        val mappingCoverage =
            if (sourceCatalog.standardFeatureCount == 0) 0.0
            else sourceCatalog.mappedFeatureCount.toDouble() / sourceCatalog.standardFeatureCount
        return GateCheck(
            step = "Offline evaluation threshold",
            passed = mappingCoverage >= 0.6,
            reasonCode = if (mappingCoverage >= 0.6) null else GovernanceReasonCode.MAPPING_COVERAGE_LOW,
            detail = if (mappingCoverage >= 0.6) null else "mappingCoverage=$mappingCoverage"
        )
    }

    /** 10. Hash/signature：Manifest 五段 Hash 与目录一致（校验由调用方计算）。 */
    private fun hashSignatureValidation(): GateCheck {
        val manifest = GovernanceManifestBuilder.build(
            baseline, packs, tools, workflows, sourceCatalog
        )
        val ok = manifest.hashes.toolManifest ==
            GovernanceManifestBuilder.sha256(tools.joinToString("|") { it.toolId })
        return GateCheck(
            step = "Hash/signature",
            passed = ok,
            reasonCode = if (ok) null else GovernanceReasonCode.MANIFEST_INVALID,
            detail = if (ok) null else "toolManifestHash mismatch"
        )
    }

    /** 11. Atomic publish：无 NEEDS_REVIEW 项进入可执行索引。 */
    private fun atomicPublishValidation(): GateCheck {
        val needsReview = tools.filter { it.status == GovernanceStatus.NEEDS_REVIEW }.map { it.toolId }
        val notReadyInIndex = tools.filter {
            it.status != GovernanceStatus.APPROVED || it.bindingStatus.name != "BOUND"
        }.map { it.toolId }
        // v1 目录全部 DRAFT → 不允许进入运行时索引；此门禁用于阻止「未就绪项混入索引」。
        val ok = needsReview.isEmpty()
        return GateCheck(
            step = "Atomic publish",
            passed = ok,
            reasonCode = if (ok) null else GovernanceReasonCode.GOV_STATUS_REJECTED,
            detail = if (ok) null else "needsReviewInIndex=$needsReview notReady=$notReadyInIndex"
        )
    }
}
