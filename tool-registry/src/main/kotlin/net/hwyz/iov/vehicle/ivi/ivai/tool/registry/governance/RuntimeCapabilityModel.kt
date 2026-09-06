package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.capability.CapabilityPack
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionRange

/**
 * 治理运行时模式（IVI-IVAI-DSN-CR-010）。
 *
 * - [STRICT]：所有发布构建的唯一模式。统一候选集只包含
 *   APPROVED + enabled + 唯一有效 Binding 的资产；任何开发桩豁免进入
 *   STRICT 都会触发 IVAI-GOV-004。
 * - [DEVELOPMENT_STUB]：仅限 debug/test、mock-vehicle 与显式 Feature Flag。
 *   允许白名单 DRAFT 资产作为 STUB 候选参与 L0/L1 骨架验证，但只能调用
 *   Mock Adapter；DRAFT 原始状态必须保留，不得原地强改为 APPROVED。
 */
enum class GovernanceRuntimeMode {
    STRICT,
    DEVELOPMENT_STUB
}

/**
 * 开发桩豁免记录（IVI-IVAI-DSN-CR-010）。
 *
 * 豁免必须记录范围（[scopeToolIds]）、原因、责任人、起止版本与过期条件。
 * [endVersion] 为 null 表示无期限（仅 STUB 模式允许）；[expiresAt] 为 null
 * 表示无绝对过期时间。STRICT / Release 构建检测到任何豁免、过期豁免或
 * 无期限豁免都必须失败（IVAI-GOV-004）。
 */
data class StubExemption(
    val exemptionId: String,
    val scopeToolIds: Set<String>,
    val reason: String,
    val owner: String,
    val startVersion: String,
    val endVersion: String? = null,
    val expiresAt: String? = null
) {
    /** 在当前软件版本下是否仍有效（STUB 内使用）。 */
    fun validFor(softwareVersion: String?): Boolean {
        val minOk = softwareVersion == null || VersionRange.compare(softwareVersion, startVersion) >= 0
        val maxOk = endVersion == null || softwareVersion == null ||
            VersionRange.compare(softwareVersion, endVersion) < 0
        val notExpired = expiresAt == null || runCatching {
            !java.time.Instant.parse(expiresAt).isBefore(java.time.Instant.now())
        }.getOrDefault(true)
        return minOk && maxOk && notExpired
    }
}

/**
 * 运行时装配环境（IVI-IVAI-DSN-CR-010）。一次请求只解析出一个环境，
 * 装配结果在请求内保持不变（请求内一致性）。
 */
data class RuntimeEnvironment(
    val vehicleModel: String? = null,
    val softwareVersion: String? = null,
    val enabledFeatures: Set<String> = emptySet(),
    /** Domain/Pack 选择后的 Pack 集合（由 CapabilityPackSelector 提供）。 */
    val selectedPackIds: Set<String> = emptySet(),
    val mode: GovernanceRuntimeMode = GovernanceRuntimeMode.STRICT,
    val stubExemptions: List<StubExemption> = emptyList()
)

/**
 * CR-010 错误码（IVI-IVAI-DSN-CR-010 错误码表新增）：
 *
 * | 错误码 | 含义 |
 * | IVAI-CAP-003 | Pack、Registry、Alias 与 Binding 的 canonical 候选引用不闭合 |
 * | IVAI-ROUTE-003 | 确定性匹配产生冲突，不能唯一确定 Tool/Workflow |
 * | IVAI-ROUTE-004 | 高频明确表达缺少确定性匹配元数据 |
 * | IVAI-GOV-003 | 非法将 DRAFT 资产提升为量产可执行状态 |
 * | IVAI-GOV-004 | 开发桩豁免缺失、过期或进入发布构建 |
 * | IVAI-ALIAS-001 | 旧 ID 与 canonical ID 产生重复或冲突映射 |
 */
object Cr010ErrorCodes {
    const val CAP_CANONICAL_UNCLOSED = "IVAI-CAP-003"
    const val ROUTE_CONFLICT = "IVAI-ROUTE-003"
    const val ROUTE_COVERAGE_MISSING = "IVAI-ROUTE-004"
    const val GOV_DRAFT_PROMOTED = "IVAI-GOV-003"
    const val GOV_STUB_EXEMPTION_INVALID = "IVAI-GOV-004"
    const val ALIAS_CONFLICT = "IVAI-ALIAS-001"
}

/**
 * 统一运行时能力集合（IVI-IVAI-DSN-CR-010）。
 *
 * 选中 Capability Pack 后，全部 APPROVED + enabled + 唯一有效 Binding 的 Tool
 * （STUB 模式另含白名单 DRAFT）经旧 ID canonicalize 与去重后形成的统一
 * runtimeCandidateToolIds；L0 与 L1 都只在该集合或其 Top-K 子集内工作，
 * 不存在独立 L0 白名单。
 */
data class RuntimeCapabilitySet(
    val selectedPackIds: Set<String>,
    val runtimeCandidateToolIds: Set<String>,
    val runtimeCandidateWorkflowIds: Set<String>,
    val governanceVersion: String,
    /** CR-010 旧 ID → canonical 迁移版本（旧空调 P0 集迁移）。 */
    val migrationVersion: String? = null,
    /** 本次候选中被开发桩豁免放行的 DRAFT Tool（仅 STUB）。 */
    val stubExemptedToolIds: Set<String> = emptySet()
) {
    /** 稳定可复现的候选集 Hash（可观测性 / 一致性校验，CR-010）。 */
    val runtimeCandidateToolIdsHash: String by lazy {
        stableHash(runtimeCandidateToolIds)
    }

    companion object {
        val EMPTY = RuntimeCapabilitySet(
            selectedPackIds = emptySet(),
            runtimeCandidateToolIds = emptySet(),
            runtimeCandidateWorkflowIds = emptySet(),
            governanceVersion = "0"
        )

        /** FNV-1a 64 稳定散列（不依赖 JVM hashCode，跨进程可复现）。 */
        fun stableHash(ids: Set<String>): String {
            val data = ids.sorted().joinToString("\u0000").toByteArray(Charsets.UTF_8)
            var hash = -0x340d631b7bdddcdbL // FNV offset basis
            for (b in data) {
                hash = hash xor (b.toLong() and 0xff)
                hash *= 0x100000001b3L // FNV prime
            }
            return java.lang.Long.toHexString(hash)
        }
    }
}

/**
 * 治理目录（IVI-IVAI-DSN-CR-010 装配输入）：Pack、Tool 与 Workflow 的
 * 统一只读视图，供 [RuntimeCapabilityAssembler] 计算运行时候选集。
 */
data class GovernanceCatalog(
    val packs: List<CapabilityPack>,
    val tools: List<ToolGovernanceSpec>,
    val workflows: List<WorkflowGovernanceSpec>
)
