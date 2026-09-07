package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions

/**
 * L0 确定性资格结论（IVI-IVAI-DSN-CR-013）。
 *
 * 160 个 Tool 每项都必须完成一次资格评审并给出唯一结论：
 *  - [SUPPORTED]：对象、动作、槽位稳定，可由受控规则唯一、安全、完整解析，
 *    具备有效规则时可进入 FastIntentMatcher 的确定性候选集。
 *  - [NOT_SUPPORTED]：开放检索 / 强上下文 / 隐式偏好 / 高歧义高风险，不得由 L0
 *    规则产生候选；仍保留在统一运行时候选集（L1/追问/拒绝）。
 *  - [NEEDS_REVIEW]：词表未闭合或参数互斥未固化，暂不生成生产规则，可在离线
 *    评测中验证。
 */
enum class DeterministicSupport {
    SUPPORTED,
    NOT_SUPPORTED,
    NEEDS_REVIEW;

    /** 该资格结论是否允许进入生产确定性匹配集合。 */
    val productionEligible: Boolean get() = this == SUPPORTED

    companion object {
        fun fromName(name: String): DeterministicSupport = when (name) {
            "SUPPORTED" -> SUPPORTED
            "NOT_SUPPORTED" -> NOT_SUPPORTED
            "NEEDS_REVIEW" -> NEEDS_REVIEW
            else -> throw IllegalArgumentException("未知 L0 确定性资格: $name")
        }
    }
}

/**
 * L0 评审状态（IVI-IVAI-DSN-CR-013 Catalog L0评审状态 字段）。
 * 生成草案为 [DRAFT]；词表未闭合项为 [NEEDS_REVIEW]；评审通过为 [APPROVED]；
 * 规则下线为 [DEPRECATED]。
 */
enum class L0ReviewStatus {
    DRAFT,
    NEEDS_REVIEW,
    APPROVED,
    DEPRECATED;

    companion object {
        fun fromName(name: String): L0ReviewStatus = when (name) {
            "DRAFT" -> DRAFT
            "NEEDS_REVIEW" -> NEEDS_REVIEW
            "APPROVED" -> APPROVED
            "DEPRECATED" -> DEPRECATED
            else -> throw IllegalArgumentException("未知 L0 评审状态: $name")
        }
    }
}

/**
 * L0 确定性资格画像（IVI-IVAI-DSN-CR-013 DeterministicIntentProfile）。
 *
 * 由 IVAI Tool Catalog v1 的 L0 治理字段经 L0 Qualification Validator 校验、
 * Deterministic Rule Compiler 编译生成；是运行时 [DeterministicIntentCatalog]
 * 的最小单元，也是 Contract Test Generator 的输入。
 *
 * [rules] 为编译后的 DeterministicIntentRule；[positiveExamples] /
 * [negativeExamples] 为正负例测试资产；[conflictToolIds] 为必须参与跨 Tool /
 * Workflow 全局消歧的同对象/同能力家族 canonical ID；[ruleVersion] 与
 * [reviewStatus] 参与 Manifest/Hash 一致性校验。
 */
data class DeterministicIntentProfile(
    val toolId: String,
    val support: DeterministicSupport,
    val supportReason: String,
    val rules: List<DeterministicIntentRule>,
    val positiveExamples: List<String>,
    val negativeExamples: List<String>,
    val conflictToolIds: Set<String>,
    val ruleVersion: String,
    val reviewStatus: L0ReviewStatus
) {
    val productionEnabled: Boolean
        get() = support == DeterministicSupport.SUPPORTED &&
            reviewStatus != L0ReviewStatus.DEPRECATED &&
            rules.isNotEmpty()
}
