package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BindingStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus

/**
 * 统一运行时能力装配器（IVI-IVAI-DSN-CR-010）。
 *
 * 消费 Catalog、Alias 与运行环境，输出去重后的 canonical runtimeCandidateToolIds。
 * 单元测试、集成测试与 AgentService.buildAgentGraph 必须使用同一实现，
 * 禁止测试继续使用旧 P0 Catalog 而实际运行使用纯治理 Catalog。
 *
 * 装配语义：
 *  - Pack 过滤由 CapabilityPackSelector 完成，[RuntimeEnvironment.selectedPackIds]
 *    传入选中 Pack；本装配器只负责 Pack 内 Tool/Workflow 的治理与 canonical 收敛。
 *  - 候选 = APPROVED + enabled（Pack 级）+ 唯一有效 Binding；DEVELOPMENT_STUB
 *    模式额外放行白名单 DRAFT（仅 Mock Adapter，原始 DRAFT 状态保留）。
 *  - 旧 ID / Function-ID / 表达在候选构建前 canonicalize，同一输入只生成一个
 *    canonical 候选；双注册 / Alias 冲突 / 引用不闭合 → 构建失败。
 */
interface RuntimeCapabilityAssembler {
    fun assemble(
        catalog: GovernanceCatalog,
        aliases: ToolAliasCatalog,
        environment: RuntimeEnvironment
    ): RuntimeCapabilitySet
}

/**
 * 装配失败（IVI-IVAI-DSN-CR-010 错误码：IVAI-CAP-003 / IVAI-ALIAS-001 /
 * IVAI-GOV-004）。候选集构建是确定性步骤：引用不闭合、双注册、Alias 冲突或
 * 开发桩泄漏到 STRICT 时立即失败，不允许带病进入路由。
 */
class RuntimeAssemblyException(
    val errorCode: String,
    message: String
) : RuntimeException(message)

/**
 * 默认装配器实现（IVI-IVAI-DSN-CR-010 + CR-013）。
 *
 * CR-013：确定性候选集 = 运行时合法候选 ∩ 治理 Profile 投影（SUPPORTED +
 * Profile APPROVED + 版本/Hash 有效），不是手工维护的固定白名单。
 */
class DefaultRuntimeCapabilityAssembler(
    private val deterministicCatalog: DeterministicIntentCatalog =
        DeterministicIntentCatalog.build()
) : RuntimeCapabilityAssembler {

    override fun assemble(
        catalog: GovernanceCatalog,
        aliases: ToolAliasCatalog,
        environment: RuntimeEnvironment
    ): RuntimeCapabilitySet {
        val mode = environment.mode

        // 1) STRICT 下任何开发桩豁免 → IVAI-GOV-004（发布构建不允许开发桩）。
        if (mode == GovernanceRuntimeMode.STRICT && environment.stubExemptions.isNotEmpty()) {
            throw RuntimeAssemblyException(
                Cr010ErrorCodes.GOV_STUB_EXEMPTION_INVALID,
                "STRICT 模式不允许开发桩豁免进入统一候选集: " +
                    environment.stubExemptions.map { it.exemptionId }
            )
        }

        val selectedPacks = catalog.packs.filter { it.packId in environment.selectedPackIds }
        val scopeToolIds = selectedPacks.flatMap { it.toolIds }.toSet()
        val scopeWorkflowIds = selectedPacks.flatMap { it.workflowIds }.toSet()

        // 2) 候选收敛：APPROVED + BOUND；STUB 模式额外放行白名单 DRAFT。
        val validExemptions = environment.stubExemptions.filter { it.validFor(environment.softwareVersion) }
        val exemptedToolIds = validExemptions.flatMap { it.scopeToolIds }.toSet()
        val directCandidates = linkedSetOf<String>()
        val stubExempted = linkedSetOf<String>()
        for (spec in catalog.tools) {
            if (spec.toolId !in scopeToolIds) continue
            val approved = spec.status == GovernanceStatus.APPROVED &&
                spec.bindingStatus == BindingStatus.BOUND
            when {
                approved -> directCandidates += spec.toolId
                mode == GovernanceRuntimeMode.DEVELOPMENT_STUB &&
                    spec.toolId in exemptedToolIds -> {
                    directCandidates += spec.toolId
                    stubExempted += spec.toolId
                }
                else -> Unit
            }
        }

        // 3) 旧 ID / Function-ID / 表达 canonicalize（候选构建前）。
        val legacyToCanonical = aliases.legacyToCanonical()

        // 3a) Alias 冲突：同一旧 ID 映射到多个 canonical（目录本身不允许）。
        val duplicateAliases = legacyToCanonical.keys.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicateAliases.isNotEmpty()) {
            throw RuntimeAssemblyException(Cr010ErrorCodes.ALIAS_CONFLICT, "Alias 冲突（重复旧 ID）: $duplicateAliases")
        }

        // 3b) 引用不闭合：Alias 的 canonical 目标必须存在于治理目录 → IVAI-CAP-003。
        val catalogIds = catalog.tools.map { it.toolId }.toSet()
        for ((legacyId, canonicalId) in legacyToCanonical) {
            if (canonicalId !in catalogIds) {
                throw RuntimeAssemblyException(
                    Cr010ErrorCodes.CAP_CANONICAL_UNCLOSED,
                    "canonical 候选引用不闭合: $legacyId → $canonicalId（Catalog 无此 Tool）"
                )
            }
        }

        // 3c) 旧/新 ID 双注册：同一 canonical 目标同时存在旧 ID 与 canonical ID
        //     候选 → IVAI-ALIAS-001（不得双匹配 / 重复执行）。
        val canonicalToLegacy = legacyToCanonical.entries.groupBy { it.value }
        for ((canonicalId, mappings) in canonicalToLegacy) {
            val hasCanonical = canonicalId in directCandidates
            val hasLegacy = mappings.any { it.key in directCandidates }
            if (hasCanonical && hasLegacy) {
                throw RuntimeAssemblyException(
                    Cr010ErrorCodes.ALIAS_CONFLICT,
                    "旧 ID 与 canonical ID 双注册: ${mappings.map { it.key }} 与 $canonicalId"
                )
            }
        }

        // 3d) canonicalize 并去重：旧 ID 候选映射为 canonical，同一输入只生成一个候选。
        val canonicalCandidates = directCandidates.map { legacyToCanonical[it] ?: it }.toSet()

        // 4) Workflow 候选：APPROVED + BOUND（STUB 亦不放行 DRAFT Workflow）。
        val workflowCandidates = catalog.workflows
            .filter {
                it.workflowId in scopeWorkflowIds &&
                    it.status == GovernanceStatus.APPROVED &&
                    it.bindingStatus == BindingStatus.BOUND
            }
            .map { it.workflowId }
            .toSet()

        return RuntimeCapabilitySet(
            selectedPackIds = selectedPacks.map { it.packId }.toSet(),
            runtimeCandidateToolIds = canonicalCandidates,
            runtimeCandidateWorkflowIds = workflowCandidates,
            governanceVersion = selectedPacks.maxOfOrNull { it.governanceVersion } ?: "0",
            migrationVersion = MIGRATION_VERSION,
            stubExemptedToolIds = stubExempted,
            // CR-013：确定性候选 = 运行时合法候选 ∩ 治理 Profile 投影。
            deterministicCandidateToolIds =
                canonicalCandidates.intersect(deterministicCatalog.productionToolIds),
            deterministicCatalogVersion = deterministicCatalog.ruleVersion,
            deterministicCatalogHash = deterministicCatalog.contentHash
        )
    }

    companion object {
        /** CR-010 旧空调 P0 集 → canonical 迁移版本标识。 */
        const val MIGRATION_VERSION = "ivai-climate-migration-v1"
    }
}
