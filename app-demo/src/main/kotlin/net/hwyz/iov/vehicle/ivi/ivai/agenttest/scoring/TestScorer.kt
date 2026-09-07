package net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.ActualTarget
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.canonical.ParameterCanonicalizer
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
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
 * 五维评分结果：tier / domain / capabilityPack / target / arguments 各 0 或 1 分。
 */
data class AgentTestScore(
    val tier: FieldMatch,
    val domain: FieldMatch,
    val capabilityPack: FieldMatch,
    val target: FieldMatch,
    val arguments: FieldMatch
) {
    val total: Int
        get() = listOf(tier, domain, capabilityPack, target, arguments).count { it.matched }
}

/**
 * 从快照投影出的可评分实际值（IVI-IVAI-DSN-CR-012）。UI 不得根据回复文案反向推断。
 */
data class ScoredActual(
    val finalTier: IntentTier? = null,
    val finalDomain: BusinessDomainId? = null,
    val actualCapabilityPacks: Set<String> = emptySet(),
    val target: ActualTarget? = null,
    val arguments: JsonObject? = null
)

/**
 * 五维评分器（IVI-IVAI-DSN-CR-012）：
 * 1. Tier：比较最终实际层级，不比较 initialTier。
 * 2. Domain：比较 finalDomain。
 * 3. Capability Pack：执行 expectedCapabilityPack in actualCapabilityPacks。
 * 4. Target：类型和 canonical ID 均相等；Tool 与同名 Workflow 不得互相匹配。
 * 5. Arguments：以 expectedArguments 为断言集合逐项 canonical 比较；任一字段
 *    缺失或不等则该维度 0 分。空 expectedArguments 表示预期无业务参数。
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
        return AgentTestScore(tier, domain, pack, target, args)
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
