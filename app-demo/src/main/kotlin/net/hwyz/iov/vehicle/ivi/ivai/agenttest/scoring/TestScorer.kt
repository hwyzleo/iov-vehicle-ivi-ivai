package net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.ActualTarget
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.canonical.ParameterCanonicalizer
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ExpectedOutcome
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId

/**
 * 单维度匹配结果（IVI-IVAI-DSN-CR-012 五维评分模型）。[expected] / [actual]
 * 以 JSON 形式保留便于 UI 展示差异；[reason] 为不匹配时的诊断原因。
 */
data class FieldMatch(
    val matched: Boolean,
    val expected: JsonElement? = null,
    val actual: JsonElement? = null,
    val reason: String? = null
)

/**
 * 六维评分结果：tier / domain / capabilityPack / target / arguments + CR-019
 * 可选 outcome。Outcome 作为**独立通过门槛**（Tier 与业务 Outcome 分别计算），
 * 不占分——保持 5 分制，避免 UI/统计出现 6/5。
 */
data class AgentTestScore(
    val tier: FieldMatch,
    val domain: FieldMatch,
    val capabilityPack: FieldMatch,
    val target: FieldMatch,
    val arguments: FieldMatch,
    /** CR-019：V2 业务 Outcome（EXECUTE/NEED_DIALOGUE/REJECTED），V1 为 null。 */
    val outcome: FieldMatch? = null
) {
    /** 五维总分（0..5）。 */
    val total: Int
        get() = listOf(tier, domain, capabilityPack, target, arguments).count { it.matched }

    /** 满分恒为 5（UI 单条显示 / 批次统计 / 导出均 5 分制，杜绝 6/5）。 */
    val maxScore: Int
        get() = 5

    /**
     * 最终通过判定：五维全对 且 业务 Outcome 匹配（V1 无 Outcome 时仅五维全对）。
     * 五维全对但 Outcome 不匹配 → 5/5 显示但 FAILED（诊断 OUTCOME 维度说明原因）。
     */
    val passed: Boolean
        get() = total == maxScore && (outcome?.matched ?: true)
}

/**
 * CR-019：业务 Outcome 投影（处理 Tier 与业务 Outcome 拆分）。
 *
 * 从终态推导业务 Outcome：EXECUTE←SUCCEEDED；NEED_DIALOGUE←追问/等待确认；
 * REJECTED←拒绝；其余（REPLIED/FAILED/TIMEOUT/CANCELLED）无法归入三类 → null。
 */
object OutcomeProjector {

    fun fromTerminalStatus(status: EvaluationTerminalStatus?): ExpectedOutcome? = when (status) {
        EvaluationTerminalStatus.SUCCEEDED -> ExpectedOutcome.EXECUTE
        EvaluationTerminalStatus.NEED_DIALOGUE,
        EvaluationTerminalStatus.WAITING_CONFIRMATION -> ExpectedOutcome.NEED_DIALOGUE
        EvaluationTerminalStatus.REJECTED -> ExpectedOutcome.REJECTED
        else -> null
    }
}

/**
 * 从快照投影出的可评分实际值（IVI-IVAI-DSN-CR-012）。UI 不得根据回复文案反向推断。
 */
data class ScoredActual(
    val finalTier: IntentTier? = null,
    val finalDomain: BusinessDomainId? = null,
    val actualCapabilityPacks: Set<String> = emptySet(),
    val target: ActualTarget? = null,
    val arguments: JsonObject? = null,
    /** CR-019：终态（Outcome 评分依据）。 */
    val terminalStatus: EvaluationTerminalStatus? = null,
    /** CR-019：终态 reasonCode（NEED_DIALOGUE/REJECTED 完整率校验）。 */
    val reasonCode: String? = null
) {
    /** CR-019：投影业务 Outcome（null 表示无法归入三类）。 */
    val runtimeOutcome: ExpectedOutcome?
        get() = OutcomeProjector.fromTerminalStatus(terminalStatus)
}

/**
 * 评分器（CR-012 五维 + CR-019 Outcome 独立评分）：
 * 1. Tier：比较最终实际层级，不比较 initialTier。
 * 2. Domain：比较 finalDomain。
 * 3. Capability Pack：执行 expectedCapabilityPack in actualCapabilityPacks。
 * 4. Target：类型和 canonical ID 均相等；Tool 与同名 Workflow 不得互相匹配。
 * 5. Arguments：以 expectedArguments 为断言集合逐项 canonical 比较（Schema-aware
 *    Comparator）；数值 5 与 5.0 等价。任一字段缺失或不等则该维度 0 分。
 * 6.（CR-019 V2）Outcome：EXECUTE 必须有 Target 与参数断言；NEED_DIALOGUE/
 *    REJECTED 必须无可执行 Target 并校验 runtimeOutcome 与 expectedReasonCode。
 */
object TestScorer {

    fun score(case: AgentTestCase, actual: ScoredActual): AgentTestScore {
        val tier = FieldMatch(
            matched = actual.finalTier == case.expectedTier,
            expected = JsonPrimitive(case.expectedTier.name),
            actual = actual.finalTier?.let { JsonPrimitive(it.name) },
            reason = mismatchReason("层级", case.expectedTier.name, actual.finalTier?.name)
        )
        val domain = FieldMatch(
            matched = actual.finalDomain == case.expectedDomain,
            expected = JsonPrimitive(case.expectedDomain.name),
            actual = actual.finalDomain?.let { JsonPrimitive(it.name) },
            reason = mismatchReason("领域", case.expectedDomain.name, actual.finalDomain?.name)
        )
        val pack = FieldMatch(
            matched = case.expectedCapabilityPack in actual.actualCapabilityPacks,
            expected = JsonPrimitive(case.expectedCapabilityPack),
            actual = JsonPrimitive(actual.actualCapabilityPacks.joinToString(",")),
            reason = if (case.expectedCapabilityPack in actual.actualCapabilityPacks) null
            else "预期能力包 ${case.expectedCapabilityPack} 不在实际集合 [${actual.actualCapabilityPacks.joinToString()}] 中"
        )
        val target = targetMatch(case, actual.target)
        val args = FieldMatch(
            matched = ParameterCanonicalizer.argumentsMatch(case.expectedArguments, actual.arguments),
            expected = case.expectedArguments,
            actual = actual.arguments,
            reason = if (ParameterCanonicalizer.argumentsMatch(case.expectedArguments, actual.arguments)) null
            else "参数断言未通过（预期 ${case.expectedArguments} / 实际 ${actual.arguments ?: "无"}）"
        )
        return AgentTestScore(tier, domain, pack, target, args, outcomeMatch(case, actual, target))
    }

    /**
     * CR-019：业务 Outcome 评分（V1 用例 expectedOutcome 为 null → 返回 null，不参与总分）。
     *  - EXECUTE：必须实际产生可执行 Target（target 维度已断言参数）；
     *  - NEED_DIALOGUE/REJECTED：必须无可执行 Target，并校验 runtimeOutcome 与
     *    expectedReasonCode（reasonCode 完整率校验，IVAI-TEST-OUTCOME-001 兜底）。
     */
    private fun outcomeMatch(case: AgentTestCase, actual: ScoredActual, target: FieldMatch): FieldMatch? {
        val expected = case.expectedOutcome ?: return null
        val actualOutcome = actual.runtimeOutcome
        val executableTargetPresent = actual.target != null
        val targetOk = if (expected == ExpectedOutcome.EXECUTE) {
            executableTargetPresent
        } else {
            // NEED_DIALOGUE / REJECTED 必须无可执行 Target。
            !executableTargetPresent
        }
        val reasonOk = case.expectedReasonCode == null ||
            (actual.reasonCode != null && case.expectedReasonCode == actual.reasonCode)
        val matched = actualOutcome == expected && targetOk && reasonOk
        return FieldMatch(
            matched = matched,
            expected = JsonPrimitive(expected.name),
            actual = actualOutcome?.let { JsonPrimitive(it.name) } ?: JsonPrimitive("NONE"),
            reason = when {
                actualOutcome != expected ->
                    "Outcome 预期 $expected，实际 ${actualOutcome?.name ?: "无法归入三类"}"
                !targetOk -> "Outcome=$expected 时目标可执行性不符（预期" +
                    if (expected == ExpectedOutcome.EXECUTE) "有目标" else "无目标" +
                    "，实际 ${actual.target?.let { "${it.type}:${it.id}" } ?: "无"}）"
                !reasonOk -> "reasonCode 预期 ${case.expectedReasonCode}，实际 ${actual.reasonCode ?: "无"}"
                else -> null
            }
        )
    }

    private fun targetMatch(case: AgentTestCase, actualTarget: ActualTarget?): FieldMatch {
        val expected = case.expectedTarget
        if (expected == null) {
            return FieldMatch(
                matched = actualTarget == null,
                expected = null,
                actual = actualTarget?.let { JsonPrimitive("${it.type}:${it.id}") },
                reason = if (actualTarget == null) null
                else "预期不产生目标，实际产生 ${actualTarget.type}:${actualTarget.id}"
            )
        }
        val matched = actualTarget != null &&
            actualTarget.type == expected.type &&
            actualTarget.id == expected.id
        return FieldMatch(
            matched = matched,
            expected = JsonPrimitive("${expected.type}:${expected.id}"),
            actual = actualTarget?.let { JsonPrimitive("${it.type}:${it.id}") },
            reason = if (matched) null
            else "预期 ${expected.type}:${expected.id}，实际 ${actualTarget?.let { "${it.type}:${it.id}" } ?: "无"}"
        )
    }

    private fun mismatchReason(dimension: String, expected: String?, actual: String?): String? =
        if (expected == actual) null else "$dimension 预期 $expected，实际 ${actual ?: "无"}"
}
