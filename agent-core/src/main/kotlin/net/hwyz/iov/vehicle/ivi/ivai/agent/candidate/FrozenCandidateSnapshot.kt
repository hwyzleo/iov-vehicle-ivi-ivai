package net.hwyz.iov.vehicle.ivi.ivai.agent.candidate

import kotlinx.serialization.json.JsonObject
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSource
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.deterministic.ArgumentSource
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.TargetType

/**
 * 冻结的候选快照（IVI-IVAI-DSN-CR-016）。
 *
 * Candidate Boundary 通过时创建不可变快照：后续 Policy、Confirmation、Binding
 * 或 Adapter 失败只能更新终态和错误，不得清空快照。若 Candidate Boundary 未通过，
 * 则 Target/Arguments 保持空，并必须记录失败阶段及具体原因。
 *
 * [candidateSetHash] 为受控候选集 hash（可观测性）；[matchedRuleIds] 为命中的
 * 确定性规则 ID；[argumentSources] 为参数 → 来源映射（USER_EXPLICIT /
 * ALIAS_MAPPING / RULE_PRESET / SCHEMA_DEFAULT / MODEL_OUTPUT）。
 */
data class FrozenCandidateSnapshot(
    val type: TargetType,
    val targetId: String,
    val canonicalArguments: JsonObject,
    val candidateSetHash: String,
    val source: CandidateSource,
    val matchedRuleIds: List<String>,
    val argumentSources: Map<String, ArgumentSource>
)
