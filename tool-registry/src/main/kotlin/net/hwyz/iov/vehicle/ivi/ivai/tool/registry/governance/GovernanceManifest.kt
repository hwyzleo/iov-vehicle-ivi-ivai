package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import kotlinx.serialization.Serializable
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BindingStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus
import java.security.MessageDigest

/**
 * 来源目录统计（IVI-IVAI-DSN-CR-009 Governance Manifest sourceCatalog）。
 * 语音 AI 标准功能目录的 18 个来源领域、1,730 个标准二级功能和 3,444 条原始指令
 * 作为 source taxonomy 保留；其中 1,110 个功能已映射、620 个功能待确认。
 */
@Serializable
data class SourceCatalogStats(
    val domainCount: Int = 18,
    val standardFeatureCount: Int = 1730,
    val instructionCount: Int = 3444,
    val mappedFeatureCount: Int = 1110,
    val needsReviewFeatureCount: Int = 620
)

/** 治理目标（target，来自第一版基线）。 */
@Serializable
data class GovernanceTarget(
    val businessDomainCount: Int = 10,
    val capabilityPackCount: Int = 18,
    val toolCount: Int = 160,
    val workflowCount: Int = 18
)

/**
 * 治理实际数量（actual，**必须由构建流程计算，不得手工维护**）。
 *
 * 只有治理状态为 APPROVED 且具有当前车型、软件版本有效 Binding 的 Tool/Workflow
 * 才计入 bound 数量；待确认、无 Binding、版本不兼容和仅用于语义评测的来源项
 * 不计入可执行数量。
 */
@Serializable
data class GovernanceActual(
    val approvedToolCount: Int,
    val boundToolCount: Int,
    val approvedWorkflowCount: Int,
    val boundWorkflowCount: Int,
    val aliasCount: Int,
    val needsReviewCount: Int
)

/** 治理包各段 Hash（SHA-256 十六进制）。 */
@Serializable
data class GovernanceHashes(
    val domainManifest: String,
    val capabilityManifest: String,
    val toolManifest: String,
    val workflowManifest: String,
    val aliasManifest: String
)

/**
 * Governance Manifest（IVI-IVAI-DSN-CR-009）。
 *
 * 数量基线与统计口径存储于此，不作为代码硬编码数组长度或业务判断条件。
 * [actual] 由构建流程计算；只有 APPROVED 且有效 Binding 的资产计入 bound。
 */
@Serializable
data class GovernanceManifest(
    val baselineVersion: String,
    val sourceCatalog: SourceCatalogStats,
    val target: GovernanceTarget,
    val actual: GovernanceActual,
    val hashes: GovernanceHashes
)

/** 治理错误码（IVI-IVAI-DSN-CR-009 校验与发布门禁）。 */
object GovernanceErrorCodes {
    const val GOV_STATUS_REJECTED = "IVAI-GOV-001"
    const val BINDING_MISSING = "IVAI-BINDING-001"
}

/**
 * 治理构建器：把目录资产汇总为 Manifest，并计算各段 Hash。
 * [GovernanceActual] 必须从真实目录计算（非手工维护）。
 */
object GovernanceManifestBuilder {

    fun build(
        baseline: GovernanceBaseline,
        packs: List<CapabilityPackGovernanceSpec>,
        tools: List<ToolGovernanceSpec>,
        workflows: List<WorkflowGovernanceSpec>,
        source: SourceCatalogStats = SourceCatalogStats(),
        aliasCount: Int = 0
    ): GovernanceManifest {
        val approvedTools = tools.filter { it.status == GovernanceStatus.APPROVED }
        val boundTools = tools.filter {
            it.status == GovernanceStatus.APPROVED && it.bindingStatus == BindingStatus.BOUND
        }
        val approvedWorkflows = workflows.filter { it.status == GovernanceStatus.APPROVED }
        val boundWorkflows = workflows.filter {
            it.status == GovernanceStatus.APPROVED && it.bindingStatus == BindingStatus.BOUND
        }
        val actual = GovernanceActual(
            approvedToolCount = approvedTools.size,
            boundToolCount = boundTools.size,
            approvedWorkflowCount = approvedWorkflows.size,
            boundWorkflowCount = boundWorkflows.size,
            aliasCount = aliasCount,
            needsReviewCount = source.needsReviewFeatureCount
        )
        val hashes = GovernanceHashes(
            domainManifest = sha256(packs.map { it.domainId.name }.distinct().sorted().joinToString(",")),
            capabilityManifest = sha256(packs.joinToString("|") { it.packId }),
            toolManifest = sha256(tools.joinToString("|") { it.toolId }),
            workflowManifest = sha256(workflows.joinToString("|") { it.workflowId }),
            aliasManifest = sha256("alias-v1")
        )
        return GovernanceManifest(
            baselineVersion = baseline.baselineVersion,
            sourceCatalog = source,
            target = GovernanceTarget(
                businessDomainCount = baseline.domainTarget,
                capabilityPackCount = baseline.capabilityPackTarget,
                toolCount = baseline.toolTarget,
                workflowCount = baseline.workflowTarget
            ),
            actual = actual,
            hashes = hashes
        )
    }

    fun sha256(content: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
