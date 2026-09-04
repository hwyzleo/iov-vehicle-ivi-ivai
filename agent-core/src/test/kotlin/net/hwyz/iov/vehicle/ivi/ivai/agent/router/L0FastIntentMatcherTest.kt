package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-005 验证设计 · L0 单元测试：唯一匹配、槽位抽取、否定/多意图/缺参/冲突/
 * 版本过滤均不进入直达执行。
 */
class L0FastIntentMatcherTest {

    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
    private val matcher = DefaultFastIntentMatcher(registry)
    private val context = AgentContext(
        requestId = "r1", sessionId = "s1", source = "mock"
    )

    private suspend fun match(text: String, ctx: AgentContext = context): FastIntentMatchResult =
        matcher.match(TextNormalizer.normalize(text), ctx)

    @Test
    fun `打开空调唯一匹配 power_on`() = runTest {
        val result = match("打开空调")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.power_on", unique.candidate.toolId)
        assertEquals(CandidateSource.L0_RULE, unique.candidate.source)
        assertEquals("L0_UNIQUE_MATCH", unique.reasonCode)
    }

    @Test
    fun `主驾调到24度正确抽取位置与温度`() = runTest {
        val result = match("主驾调到24度")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.temperature_set", unique.candidate.toolId)
        assertEquals("driver", unique.candidate.arguments["position"])
        assertEquals(24.0, unique.candidate.arguments["temperature"])
    }

    @Test
    fun `空调设成25度抽取温度`() = runTest {
        val result = match("空调设成25度")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.temperature_set", unique.candidate.toolId)
        assertEquals(25.0, unique.candidate.arguments["temperature"])
    }

    @Test
    fun `温度调高一点唯一匹配升温`() = runTest {
        val result = match("温度调高一点")
        assertTrue(result is FastIntentMatchResult.Unique)
        assertEquals("climate.temperature_increase", (result as FastIntentMatchResult.Unique).candidate.toolId)
    }

    @Test
    fun `否定表达不进入直达`() = runTest {
        val input = TextNormalizer.normalize("别打开空调")
        assertTrue(input.hasNegation)
        assertTrue(match("别打开空调") is FastIntentMatchResult.NoMatch)
    }

    @Test
    fun `多意图不进入直达`() = runTest {
        val input = TextNormalizer.normalize("打开空调然后降温")
        assertTrue(input.hasMultiIntent)
        assertTrue(match("打开空调然后降温") is FastIntentMatchResult.NoMatch)
        assertTrue(match("把空调打开然后把温度调到26度") is FastIntentMatchResult.NoMatch)
    }

    @Test
    fun `缺参返回 MissingArguments`() = runTest {
        val result = match("温度调到")
        assertTrue(result is FastIntentMatchResult.MissingArguments)
        val missing = result as FastIntentMatchResult.MissingArguments
        assertEquals("climate.temperature_set", missing.toolId)
        assertTrue(missing.missing.contains("temperature"))
    }

    @Test
    fun `隐式表达我有点冷不匹配 L0`() = runTest {
        assertTrue(match("我有点冷") is FastIntentMatchResult.NoMatch)
        assertTrue(match("太热了") is FastIntentMatchResult.NoMatch)
    }

    @Test
    fun `版本不匹配的工具被预过滤`() = runTest {
        // 气候工具 softwareRange = >=0.1.0；0.0.1 不满足。
        val oldCtx = AgentContext(
            requestId = "r2", sessionId = "s2", source = "mock",
            softwareVersion = "0.0.1"
        )
        assertTrue(match("打开空调", oldCtx) is FastIntentMatchResult.NoMatch)
    }

    @Test
    fun `规则冲突返回 Ambiguous 不直达`() = runTest {
        // 构造两个共享短语的不同工具 → 同一输入命中两个工具。
        val ambiguousRegistry = ToolRegistry()
            .register(climateTool("climate.a", listOf("打开空调")))
            .register(climateTool("climate.b", listOf("打开空调")))
        val ambiguousMatcher = DefaultFastIntentMatcher(ambiguousRegistry)
        val result = ambiguousMatcher.match(TextNormalizer.normalize("打开空调"), context)
        assertTrue(result is FastIntentMatchResult.Ambiguous)
        val ambiguous = result as FastIntentMatchResult.Ambiguous
        assertEquals(setOf("climate.a", "climate.b"), ambiguous.candidates.map { it.toolId }.toSet())
    }

    private fun climateTool(toolId: String, exactPhrases: List<String>) =
        net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition(
            toolId = toolId,
            functionId = null,
            name = toolId,
            description = "test",
            positiveExamples = listOf(),
            negativeExamples = listOf(),
            selectionPriority = 1,
            parameterSchema = """{"type":"object","properties":{},"required":[]}""",
            policy = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolPolicy(),
            execution = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolExecutionBinding(
                adapterId = "test", methodId = "test"
            ),
            deterministicRules = listOf(
                net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule(
                    ruleId = "rule.$toolId", toolId = toolId, exactPhrases = exactPhrases
                )
            )
        )
}
