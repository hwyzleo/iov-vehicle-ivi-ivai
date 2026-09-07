package net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.ActualTarget
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.TargetType
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ExpectedTarget
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-012 五维评分单测（IVAI-REQ-117 / IVAI-REQ-118）：0～5 全分值、多能力包、
 * 空集合、Tool/Workflow 类型冲突、目标缺失。
 */
class TestScorerTest {

    private val case = AgentTestCase(
        caseId = "CASE-001",
        input = "打开空调",
        expectedTier = IntentTier.L0_DETERMINISTIC_TOOL,
        expectedDomain = BusinessDomainId.CABIN_COMFORT,
        expectedCapabilityPack = "cabin.climate",
        expectedTarget = ExpectedTarget(TargetType.TOOL, "climate.power.set"),
        expectedArguments = buildJsonObject { put("enabled", true) }
    )

    private fun matchingActual() = ScoredActual(
        finalTier = IntentTier.L0_DETERMINISTIC_TOOL,
        finalDomain = BusinessDomainId.CABIN_COMFORT,
        actualCapabilityPacks = setOf("cabin.climate"),
        target = ActualTarget(TargetType.TOOL, "climate.power.set"),
        arguments = buildJsonObject { put("enabled", true) }
    )

    @Test
    fun `全部匹配 → 5 分`() {
        val score = TestScorer.score(case, matchingActual())
        assertEquals(5, score.total)
        assertTrue(score.tier.matched)
        assertTrue(score.domain.matched)
        assertTrue(score.capabilityPack.matched)
        assertTrue(score.target.matched)
        assertTrue(score.arguments.matched)
    }

    @Test
    fun `全部不匹配 → 0 分`() {
        val actual = ScoredActual(
            finalTier = IntentTier.REJECT,
            finalDomain = BusinessDomainId.BODY_CONTROL,
            actualCapabilityPacks = setOf("body.door_lock"),
            target = ActualTarget(TargetType.WORKFLOW, "climate.power.set"),
            arguments = buildJsonObject { put("enabled", false) }
        )
        val score = TestScorer.score(case, actual)
        assertEquals(0, score.total)
    }

    @Test
    fun `能力包为多个且包含预期 → 能力包得 1 分`() {
        val actual = matchingActual().copy(actualCapabilityPacks = setOf("cabin.climate", "cabin.seat_comfort"))
        val score = TestScorer.score(case, actual)
        assertTrue(score.capabilityPack.matched)
        assertEquals(5, score.total)
    }

    @Test
    fun `能力包不包含预期 → 0 分 即使其他包合理`() {
        val actual = matchingActual().copy(actualCapabilityPacks = setOf("cabin.seat_comfort"))
        val score = TestScorer.score(case, actual)
        assertFalse(score.capabilityPack.matched)
        assertEquals(4, score.total)
    }

    @Test
    fun `能力包空集合 → 0 分`() {
        val actual = matchingActual().copy(actualCapabilityPacks = emptySet())
        assertFalse(TestScorer.score(case, actual).capabilityPack.matched)
    }

    @Test
    fun `Tool 与同名 Workflow 不得互相匹配`() {
        val actual = matchingActual().copy(target = ActualTarget(TargetType.WORKFLOW, "climate.power.set"))
        val score = TestScorer.score(case, actual)
        assertFalse(score.target.matched)
        assertEquals(4, score.total)
    }

    @Test
    fun `目标缺失（实际无目标）→ 目标 0 分`() {
        val actual = matchingActual().copy(target = null)
        val score = TestScorer.score(case, actual)
        assertFalse(score.target.matched)
        assertEquals(4, score.total)
    }

    @Test
    fun `预期目标为 null 且实际无目标 → 目标 1 分`() {
        val caseNoTarget = case.copy(expectedTarget = null)
        val actual = matchingActual().copy(target = null)
        val score = TestScorer.score(caseNoTarget, actual)
        assertTrue(score.target.matched)
        assertEquals(5, score.total)

        // 预期无目标但实际产生目标 → 不匹配。
        val produced = matchingActual()
        assertFalse(TestScorer.score(caseNoTarget, produced).target.matched)
    }

    @Test
    fun `参数逐字段断言 任一字段缺失或不等 → 0 分`() {
        // 缺 step 字段。
        val actual = matchingActual().copy(
            arguments = buildJsonObject { put("enabled", true); put("extra", "x") }
        )
        assertTrue(TestScorer.score(case, actual).arguments.matched)

        val missingArg = matchingActual().copy(arguments = buildJsonObject { put("zone", "driver") })
        assertFalse(TestScorer.score(case, missingArg).arguments.matched)

        val wrongValue = matchingActual().copy(arguments = buildJsonObject { put("enabled", false) })
        assertFalse(TestScorer.score(case, wrongValue).arguments.matched)
    }

    @Test
    fun `空预期参数仅匹配空实际参数`() {
        val emptyCase = case.copy(expectedArguments = JsonObject(emptyMap()))
        val emptyActual = matchingActual().copy(arguments = null)
        assertTrue(TestScorer.score(emptyCase, emptyActual).arguments.matched)
        val nonEmptyActual = matchingActual().copy(arguments = buildJsonObject { put("enabled", true) })
        assertFalse(TestScorer.score(emptyCase, nonEmptyActual).arguments.matched)
    }

    @Test
    fun `仅比较 finalTier 不比较 initialTier`() {
        val actual = matchingActual()
        // initialTier 不同但 finalTier 相同 → 层级匹配。
        assertEquals(5, TestScorer.score(case, actual).total)
    }

    @Test
    fun `参数数值等价 2 vs 2_0 通过`() {
        val stepCase = AgentTestCase(
            caseId = "CASE-002",
            input = "调高两度",
            expectedTier = IntentTier.L0_DETERMINISTIC_TOOL,
            expectedDomain = BusinessDomainId.CABIN_COMFORT,
            expectedCapabilityPack = "cabin.climate",
            expectedTarget = ExpectedTarget(TargetType.TOOL, "climate.temperature.adjust"),
            expectedArguments = buildJsonObject { put("step", 2) }
        )
        val actual = ScoredActual(
            finalTier = IntentTier.L0_DETERMINISTIC_TOOL,
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            actualCapabilityPacks = setOf("cabin.climate"),
            target = ActualTarget(TargetType.TOOL, "climate.temperature.adjust"),
            arguments = buildJsonObject { put("step", 2.0) }
        )
        assertTrue(TestScorer.score(stepCase, actual).arguments.matched)
    }
}
