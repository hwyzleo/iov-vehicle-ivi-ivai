package net.hwyz.iov.vehicle.ivi.ivai.agenttest.import

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestSuite
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ExpectedOutcome
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ExpectedTarget
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.TargetType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-019 Suite Schema v2 校验：EXECUTE 必须有 Target 与参数断言；
 * NEED_DIALOGUE/REJECTED 必须无可执行 Target。
 */
class AgentTestSuiteV2ValidationTest {

    private val validator = AgentTestSuiteValidator()
    private val json = Json { ignoreUnknownKeys = true }

    private fun suiteOf(vararg cases: AgentTestCase) = AgentTestSuite(
        suiteId = "suite-v2",
        schemaVersion = 2,
        cases = cases.toList()
    )

    private fun case(
        id: String,
        outcome: ExpectedOutcome,
        target: ExpectedTarget? = null,
        reason: String? = null
    ) = AgentTestCase(
        caseId = id,
        input = "温度调到24度",
        expectedTier = IntentTier.L1_LOCAL_TOOL_REASONING,
        expectedDomain = BusinessDomainId.CABIN_COMFORT,
        expectedCapabilityPack = "cabin.climate",
        expectedTarget = target,
        expectedArguments = buildJsonObject {},
        expectedOutcome = outcome,
        expectedReasonCode = reason
    )

    @Test
    fun `V2 合法用例通过校验`() {
        val suite = suiteOf(
            case("A", ExpectedOutcome.EXECUTE, ExpectedTarget(TargetType.TOOL, "climate.temperature.set")),
            case("B", ExpectedOutcome.NEED_DIALOGUE, null, "IVAI-TEMP-SEMANTIC-001"),
            case("C", ExpectedOutcome.REJECTED, null, "IVAI-TEMP-RANGE-001")
        )
        val result = validator.validate(suite)
        assertTrue(result.valid, "合法 V2 用例应通过: ${result.caseIssues}")
    }

    @Test
    fun `EXECUTE 缺少 Target 校验失败`() {
        val suite = suiteOf(case("A", ExpectedOutcome.EXECUTE, null))
        val result = validator.validate(suite)
        assertFalse(result.valid)
        assertTrue(result.caseIssues["A"]!!.contains("expectedTarget"), "EXECUTE 必须断言 Target")
    }

    @Test
    fun `NEED_DIALOGUE 携带 Target 校验失败`() {
        val suite = suiteOf(case("A", ExpectedOutcome.NEED_DIALOGUE, ExpectedTarget(TargetType.TOOL, "climate.temperature.set")))
        val result = validator.validate(suite)
        assertFalse(result.valid)
        assertTrue(result.caseIssues["A"]!!.contains("无可执行"), "NEED_DIALOGUE 不得有可执行 Target")
    }

    @Test
    fun `schemaVersion 2 属于支持集合`() {
        // SUPPORTED_SCHEMA_VERSION=2：解析与校验必须接受 V2（Importer 兼容）。
        val raw = """
            {
              "suiteId": "suite-v2",
              "schemaVersion": 2,
              "cases": [
                {
                  "caseId": "CASE-1",
                  "input": "温度调到24度",
                  "expectedTier": "L1_LOCAL_TOOL_REASONING",
                  "expectedDomain": "CABIN_COMFORT",
                  "expectedCapabilityPack": "cabin.climate",
                  "expectedTarget": { "type": "TOOL", "id": "climate.temperature.set" },
                  "expectedArguments": { "temperature": 24.0 },
                  "expectedOutcome": "EXECUTE"
                }
              ]
            }
        """.trimIndent()
        val suite = AgentTestSuiteParser().parse(raw)
        assertEquals(2, suite.schemaVersion)
        assertEquals(ExpectedOutcome.EXECUTE, suite.cases.single().expectedOutcome)
        assertTrue(validator.validate(suite).valid)
    }
}
