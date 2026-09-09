package net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.ActualTarget
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.TargetType
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ExpectedOutcome
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ExpectedTarget
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.TestRunSummary
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-019 评分契约 V2 单测：Tier 与业务 Outcome 独立评分。
 *
 *  - EXECUTE 必须有 Target 与参数断言；
 *  - NEED_DIALOGUE / REJECTED 必须无可执行 Target，并校验 runtimeOutcome 与 reasonCode；
 *  - 处理路径为 L1 但业务终态 REJECTED 时，Tier 评分与 Outcome 评分分别计算；
 *  - 数值 5 与 5.0 等价（Schema-aware Comparator 统一）。
 */
class TestScorerCr019Test {

    private fun v2Case(
        outcome: ExpectedOutcome,
        tier: IntentTier = IntentTier.L1_LOCAL_TOOL_REASONING,
        target: ExpectedTarget? = null,
        reasonCode: String? = null,
        args: JsonObject = buildJsonObject {}
    ) = AgentTestCase(
        caseId = "T-001",
        input = "温度调到24度",
        expectedTier = tier,
        expectedDomain = BusinessDomainId.CABIN_COMFORT,
        expectedCapabilityPack = "cabin.climate",
        expectedTarget = target,
        expectedArguments = args,
        expectedOutcome = outcome,
        expectedReasonCode = reasonCode
    )

    @Test
    fun `EXECUTE 有目标与参数 → Outcome 匹配且通过（5 分制 + 门槛）`() {
        val case = v2Case(
            ExpectedOutcome.EXECUTE,
            target = ExpectedTarget(TargetType.TOOL, "climate.temperature.set"),
            args = buildJsonObject { put("temperature", 24.0) }
        )
        val actual = ScoredActual(
            finalTier = IntentTier.L1_LOCAL_TOOL_REASONING,
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            actualCapabilityPacks = setOf("cabin.climate"),
            target = ActualTarget(TargetType.TOOL, "climate.temperature.set"),
            arguments = buildJsonObject { put("temperature", 24) },
            terminalStatus = EvaluationTerminalStatus.SUCCEEDED,
            reasonCode = "L1_TOOL_DOMAIN"
        )
        val score = TestScorer.score(case, actual)
        assertTrue(score.outcome!!.matched, "Outcome 应匹配: ${score.outcome}")
        assertEquals(5, score.total, "总分保持 5 分制（Outcome 不占分）")
        assertEquals(5, score.maxScore)
        assertTrue(score.passed, "五维全对 + Outcome 匹配才通过")
    }

    @Test
    fun `五维全对但 Outcome 不匹配 → 5 分制总分但不得通过`() {
        val case = v2Case(
            ExpectedOutcome.EXECUTE,
            target = ExpectedTarget(TargetType.TOOL, "climate.temperature.set"),
            args = buildJsonObject { put("temperature", 24.0) }
        )
        // 五维全部命中，但业务终态是 NEED_DIALOGUE（Outcome 不匹配）。
        val actual = ScoredActual(
            finalTier = IntentTier.L1_LOCAL_TOOL_REASONING,
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            actualCapabilityPacks = setOf("cabin.climate"),
            target = ActualTarget(TargetType.TOOL, "climate.temperature.set"),
            arguments = buildJsonObject { put("temperature", 24) },
            terminalStatus = EvaluationTerminalStatus.NEED_DIALOGUE,
            reasonCode = "IVAI-TEMP-SEMANTIC-001"
        )
        val score = TestScorer.score(case, actual)
        assertEquals(5, score.total, "五维全对 → 5 分（显示 5/5）")
        assertFalse(score.outcome!!.matched, "业务 Outcome 不匹配")
        assertFalse(score.passed, "5/5 显示但 Outcome 不匹配 → 不得通过（诊断 OUTCOME 维度）")
    }

    @Test
    fun `NEED_DIALOGUE 无可执行目标且 reasonCode 匹配 → Outcome 匹配`() {
        val case = v2Case(ExpectedOutcome.NEED_DIALOGUE, reasonCode = "IVAI-TEMP-SEMANTIC-001")
        val actual = ScoredActual(
            finalTier = IntentTier.L1_LOCAL_TOOL_REASONING,
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            actualCapabilityPacks = setOf("cabin.climate"),
            target = null,
            arguments = null,
            terminalStatus = EvaluationTerminalStatus.NEED_DIALOGUE,
            reasonCode = "IVAI-TEMP-SEMANTIC-001"
        )
        val score = TestScorer.score(case, actual)
        assertTrue(score.outcome!!.matched)
        assertTrue(score.tier.matched, "Tier 与 Outcome 独立评分")
    }

    @Test
    fun `NEED_DIALOGUE 处理路径 L1 且业务终态匹配 → 通过`() {
        val case = v2Case(ExpectedOutcome.NEED_DIALOGUE, reasonCode = "IVAI-TEMP-SEMANTIC-001")
        val actual = ScoredActual(
            finalTier = IntentTier.L1_LOCAL_TOOL_REASONING, // 处理路径 L1
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            actualCapabilityPacks = setOf("cabin.climate"),
            target = null,
            arguments = null,
            terminalStatus = EvaluationTerminalStatus.NEED_DIALOGUE, // 业务终态
            reasonCode = "IVAI-TEMP-SEMANTIC-001"
        )
        val score = TestScorer.score(case, actual)
        assertTrue(score.outcome!!.matched)
        assertTrue(score.tier.matched, "处理路径 L1 → Tier 维度命中")
        assertTrue(score.passed, "NEED_DIALOGUE 是预期业务终态 → 通过（5 分制 + Outcome 门槛）")
    }

    @Test
    fun `REJECTED 处理路径 L1 且业务终态匹配 → 通过`() {
        val case = v2Case(ExpectedOutcome.REJECTED, reasonCode = "IVAI-TEMP-RANGE-001")
        val actual = ScoredActual(
            finalTier = IntentTier.L1_LOCAL_TOOL_REASONING, // 处理路径 L1（越界在 L1 业务层拒绝）
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            actualCapabilityPacks = setOf("cabin.climate"),
            target = null,
            arguments = null,
            terminalStatus = EvaluationTerminalStatus.REJECTED, // 业务终态
            reasonCode = "IVAI-TEMP-RANGE-001"
        )
        val score = TestScorer.score(case, actual)
        assertTrue(score.outcome!!.matched)
        assertTrue(score.tier.matched, "处理路径 L1 → Tier 维度命中")
        assertTrue(score.passed, "REJECTED 是预期业务终态 → 通过（5 分制 + Outcome 门槛）")
    }

    @Test
    fun `NEED_DIALOGUE reasonCode 不匹配 → Outcome 不匹配`() {
        val case = v2Case(ExpectedOutcome.NEED_DIALOGUE, reasonCode = "IVAI-TEMP-SEMANTIC-001")
        val actual = ScoredActual(
            finalTier = IntentTier.L1_LOCAL_TOOL_REASONING,
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            actualCapabilityPacks = setOf("cabin.climate"),
            target = null,
            terminalStatus = EvaluationTerminalStatus.NEED_DIALOGUE,
            reasonCode = "OTHER-CODE"
        )
        val score = TestScorer.score(case, actual)
        assertFalse(score.outcome!!.matched, "reasonCode 不匹配不得通过 Outcome")
    }

    @Test
    fun `REJECTED 无可执行目标且 reasonCode 匹配 → Outcome 匹配`() {
        val case = v2Case(ExpectedOutcome.REJECTED, reasonCode = "IVAI-TEMP-RANGE-001")
        val actual = ScoredActual(
            finalTier = IntentTier.L1_LOCAL_TOOL_REASONING,
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            actualCapabilityPacks = setOf("cabin.climate"),
            target = null,
            arguments = null,
            terminalStatus = EvaluationTerminalStatus.REJECTED,
            reasonCode = "IVAI-TEMP-RANGE-001"
        )
        val score = TestScorer.score(case, actual)
        assertTrue(score.outcome!!.matched)
        assertTrue(score.passed)
    }

    @Test
    fun `REJECTED 但实际产生目标 → Outcome 不匹配`() {
        val case = v2Case(ExpectedOutcome.REJECTED, reasonCode = "IVAI-TEMP-RANGE-001")
        val actual = ScoredActual(
            finalTier = IntentTier.REJECT,
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            actualCapabilityPacks = setOf("cabin.climate"),
            target = ActualTarget(TargetType.TOOL, "climate.temperature.set"),
            arguments = buildJsonObject { put("temperature", 35.0) },
            terminalStatus = EvaluationTerminalStatus.SUCCEEDED,
            reasonCode = "L1_TOOL_DOMAIN"
        )
        val score = TestScorer.score(case, actual)
        assertFalse(score.outcome!!.matched, "REJECTED 产生目标不得通过 Outcome")
    }

    @Test
    fun `L1 处理但业务终态 REJECTED → Tier 与 Outcome 分别计算`() {
        // 处理路径为 L1 但业务终态 REJECTED：Tier 评分按 finalTier，Outcome 按终态。
        val case = v2Case(ExpectedOutcome.REJECTED, tier = IntentTier.L1_LOCAL_TOOL_REASONING, reasonCode = "IVAI-TEMP-RANGE-001")
        val actual = ScoredActual(
            finalTier = IntentTier.L1_LOCAL_TOOL_REASONING, // Tier 命中 L1
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            actualCapabilityPacks = setOf("cabin.climate"),
            target = null,
            arguments = null,
            terminalStatus = EvaluationTerminalStatus.REJECTED, // 业务终态 REJECTED
            reasonCode = "IVAI-TEMP-RANGE-001"
        )
        val score = TestScorer.score(case, actual)
        assertTrue(score.tier.matched, "Tier 按 finalTier=L1 命中")
        assertTrue(score.outcome!!.matched, "Outcome 按业务终态 REJECTED 命中")
    }

    @Test
    fun `批次统计满分恒 5 且得分率不超 100`() {
        // V2 混合批次（EXECUTE/NEED_DIALOGUE/REJECTED）：五维满分恒 5，Outcome 作为
        // 通过门槛；得分率 = scoreSum/(executed*5) 必须 ≤ 100%（杜绝 6/5 造成的虚高）。
        val execCase = v2Case(
            ExpectedOutcome.EXECUTE,
            target = ExpectedTarget(TargetType.TOOL, "climate.temperature.set"),
            args = buildJsonObject { put("temperature", 24.0) }
        )
        val execActual = ScoredActual(
            finalTier = IntentTier.L1_LOCAL_TOOL_REASONING,
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            actualCapabilityPacks = setOf("cabin.climate"),
            target = ActualTarget(TargetType.TOOL, "climate.temperature.set"),
            arguments = buildJsonObject { put("temperature", 24) },
            terminalStatus = EvaluationTerminalStatus.SUCCEEDED,
            reasonCode = "L1_TOOL_DOMAIN"
        )
        val needCase = v2Case(ExpectedOutcome.NEED_DIALOGUE, reasonCode = "IVAI-TEMP-SEMANTIC-001")
        val needActual = ScoredActual(
            finalTier = IntentTier.L1_LOCAL_TOOL_REASONING,
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            actualCapabilityPacks = setOf("cabin.climate"),
            target = null,
            arguments = null,
            terminalStatus = EvaluationTerminalStatus.NEED_DIALOGUE,
            reasonCode = "IVAI-TEMP-SEMANTIC-001"
        )
        // 该 REJECT 用例实际未拒绝（终态 NEED_DIALOGUE）→ 不得通过。
        val rejectCase = v2Case(ExpectedOutcome.REJECTED, reasonCode = "IVAI-TEMP-RANGE-001")
        val rejectFailActual = ScoredActual(
            finalTier = IntentTier.L1_LOCAL_TOOL_REASONING,
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            actualCapabilityPacks = setOf("cabin.climate"),
            target = null,
            arguments = null,
            terminalStatus = EvaluationTerminalStatus.NEED_DIALOGUE,
            reasonCode = "IVAI-TEMP-SEMANTIC-001"
        )

        val scores = listOf(
            TestScorer.score(execCase, execActual),
            TestScorer.score(needCase, needActual),
            TestScorer.score(rejectCase, rejectFailActual)
        )
        // 每条满分恒 5，不得出现 6。
        scores.forEach { assertEquals(5, it.maxScore, "满分恒为 5 分制") }
        // EXECUTE/NEED 通过，REJECT（实际未拒绝）失败。
        assertTrue(scores[0].passed)
        assertTrue(scores[1].passed)
        assertFalse(scores[2].passed, "预期 REJECTED 但实际未拒绝 → 不通过")

        val passed = scores.count { it.passed }
        val summary = TestRunSummary(
            executed = 3, passed = passed, failed = 3 - passed, skipped = 0,
            score = scores.sumOf { it.total }, maxScore = 3 * 5
        )
        assertTrue(summary.scoreRate <= 1.0, "得分率不得超过 100%（5 分制）")
        // 五维全对（得分 5/5）但业务 Outcome 不匹配的用例计入得分但不计通过。
        assertEquals(1.0, summary.scoreRate, 1e-9, "三条用例五维全对 → 得分率 100%")
        assertEquals(2, passed, "通过率（passed/executed）反映真实业务终态：REJECT 未命中不得算通过")
        assertEquals(2.0 / 3.0, passed.toDouble() / summary.executed, 1e-9)
    }
}
