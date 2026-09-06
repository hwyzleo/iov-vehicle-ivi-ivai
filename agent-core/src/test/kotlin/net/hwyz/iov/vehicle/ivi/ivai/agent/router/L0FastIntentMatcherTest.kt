package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-010 验证设计 · 统一确定性匹配单元测试。
 *
 * L0/L1 是请求解析路径而非 Tool 分类：FastIntentMatcher 只消费每个 Tool 的
 * DeterministicMatchProfile，对统一 canonical 候选集执行请求级确定性匹配。
 *  - “打开空调”→ canonical climate.power.set {enabled:true}（旧 climate.power_on
 *    已退役为 Alias，不再作为独立候选）。
 *  - negativePatterns（“关闭空调”不命中开机规则）、预置参数（enabled/direction）。
 *  - 否定 / 多意图 / 缺参 / 冲突 → 不直达。
 */
class L0FastIntentMatcherTest {

    /** CR-010：统一候选集 = 全部 160 个 canonical 治理 Tool（含确定性画像）。 */
    private val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
    private val matcher = DefaultFastIntentMatcher(registry)
    private val context = AgentContext(
        requestId = "r1", sessionId = "s1", source = "mock"
    )

    private suspend fun match(text: String, ctx: AgentContext = context): FastIntentMatchResult =
        matcher.match(TextNormalizer.normalize(text), ctx)

    @Test
    fun `打开空调唯一匹配 canonical climate_power_set 且 enabled=true`() = runTest {
        val result = match("打开空调")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.power.set", unique.candidate.toolId)
        assertEquals(true, unique.candidate.arguments["enabled"])
        assertEquals(CandidateSource.L0_RULE, unique.candidate.source)
        assertEquals("L0_UNIQUE_MATCH", unique.reasonCode)
        assertEquals("L0.power.set.on", unique.matchedPatternId)
        assertEquals("climate.power.set", unique.canonicalToolId)
        assertFalse("climate.power_on" in registry.toolIds(), "旧空调 ID 不应再作为独立可执行定义")
    }

    @Test
    fun `关闭空调经 negativePatterns 命中关机规则 enabled=false`() = runTest {
        val result = match("关闭空调")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.power.set", unique.candidate.toolId)
        assertEquals(false, unique.candidate.arguments["enabled"])
        assertEquals("L0.power.set.off", unique.matchedPatternId)
    }

    @Test
    fun `主驾调到24度抽取 zone 与温度`() = runTest {
        val result = match("主驾调到24度")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.temperature.set", unique.candidate.toolId)
        assertEquals("driver", unique.candidate.arguments["zone"])
        assertEquals(24.0, unique.candidate.arguments["temperature"])
    }

    @Test
    fun `空调设成25度抽取温度`() = runTest {
        val result = match("空调设成25度")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.temperature.set", unique.candidate.toolId)
        assertEquals(25.0, unique.candidate.arguments["temperature"])
    }

    @Test
    fun `温度调高一点唯一匹配 adjust 且 direction=increase`() = runTest {
        val result = match("温度调高一点")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.temperature.adjust", unique.candidate.toolId)
        assertEquals("increase", unique.candidate.arguments["direction"])
    }

    @Test
    fun `温度调低一点匹配 adjust 且 direction=decrease`() = runTest {
        val result = match("温度调低一点")
        assertTrue(result is FastIntentMatchResult.Unique)
        assertEquals("decrease", (result as FastIntentMatchResult.Unique).candidate.arguments["direction"])
    }

    @Test
    fun `调高两度抽取中文数字步进 step=2`() = runTest {
        val result = match("温度调高两度")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.temperature.adjust", unique.candidate.toolId)
        assertEquals(2, unique.candidate.arguments["step"])
        assertEquals("increase", unique.candidate.arguments["direction"])
    }

    @Test
    fun `调高 2 度抽取阿拉伯数字步进 step=2`() = runTest {
        val result = match("温度调高2度")
        assertTrue(result is FastIntentMatchResult.Unique)
        assertEquals(2, (result as FastIntentMatchResult.Unique).candidate.arguments["step"])
    }

    @Test
    fun `调节两档抽取步进 step=2`() = runTest {
        val result = match("温度调高两档")
        assertTrue(result is FastIntentMatchResult.Unique)
        assertEquals(2, (result as FastIntentMatchResult.Unique).candidate.arguments["step"])
    }

    @Test
    fun `越界数值不作为步进（调高到26度为绝对设定）`() = runTest {
        val result = match("温度调高到26度")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.temperature.adjust", unique.candidate.toolId)
        assertNull(unique.candidate.arguments["step"], "step 超出 Schema 值域 [1..5]，不得作为步进")
    }

    @Test
    fun `打开座椅按摩唯一匹配 seat_massage_set 且 enabled=true`() = runTest {
        val result = match("打开座椅按摩")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("seat.massage.set", unique.candidate.toolId)
        assertEquals(true, unique.candidate.arguments["enabled"])
    }

    @Test
    fun `查看空调状态走 L0 状态查询`() = runTest {
        val result = match("查看空调状态")
        assertTrue(result is FastIntentMatchResult.Unique)
        assertEquals("climate.status.query", (result as FastIntentMatchResult.Unique).candidate.toolId)
    }

    @Test
    fun `主驾升温经表达 Alias 预置 zone 与 direction 走 L0`() = runTest {
        val result = match("主驾升温")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.temperature.adjust", unique.candidate.toolId)
        assertEquals("driver", unique.candidate.arguments["zone"])
        assertEquals("increase", unique.candidate.arguments["direction"])
        assertEquals("L0.temperature.adjust.expression.driver_up", unique.matchedPatternId)
    }

    @Test
    fun `副驾降温经表达 Alias 走 L0`() = runTest {
        val result = match("副驾降温")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.temperature.adjust", unique.candidate.toolId)
        assertEquals("passenger", unique.candidate.arguments["zone"])
        assertEquals("decrease", unique.candidate.arguments["direction"])
    }

    @Test
    fun `否定表达不进入直达`() = runTest {
        val input = TextNormalizer.normalize("别打开空调")
        assertTrue(input.hasNegation)
        assertTrue(match("别打开空调") is FastIntentMatchResult.NoMatch)
    }

    @Test
    fun `多意图不进入直达`() = runTest {
        assertTrue(match("打开空调然后降温") is FastIntentMatchResult.NoMatch)
        assertTrue(match("把空调打开然后把温度调到26度") is FastIntentMatchResult.NoMatch)
    }

    @Test
    fun `缺参返回 MissingArguments`() = runTest {
        val result = match("温度调到")
        assertTrue(result is FastIntentMatchResult.MissingArguments)
        val missing = result as FastIntentMatchResult.MissingArguments
        assertEquals("climate.temperature.set", missing.toolId)
        assertTrue(missing.missing.contains("temperature"))
    }

    @Test
    fun `隐式表达我有点冷不匹配 L0`() = runTest {
        assertTrue(match("我有点冷") is FastIntentMatchResult.NoMatch)
        assertTrue(match("太热了") is FastIntentMatchResult.NoMatch)
        assertTrue(match("帮我舒服一点") is FastIntentMatchResult.NoMatch)
    }

    @Test
    fun `版本不匹配的工具被预过滤`() = runTest {
        // 构造一个软件版本约束 >=0.1.0 的 Tool；0.0.1 不满足 → 不进入 L0 匹配。
        val versionedRegistry = ToolRegistry().register(
            climateTool("climate.versioned", listOf("打开空调")).copy(
                availability = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolAvailability(
                    enabled = true,
                    vehicleModels = listOf("*"),
                    softwareRange = ">=0.1.0"
                )
            )
        )
        val versionedMatcher = DefaultFastIntentMatcher(versionedRegistry)
        val oldCtx = AgentContext(
            requestId = "r2", sessionId = "s2", source = "mock",
            softwareVersion = "0.0.1"
        )
        assertTrue(versionedMatcher.match(TextNormalizer.normalize("打开空调"), oldCtx) is FastIntentMatchResult.NoMatch)
        // 满足版本时正常命中。
        val okCtx = AgentContext(requestId = "r3", sessionId = "s3", source = "mock", softwareVersion = "0.1.0")
        assertTrue(versionedMatcher.match(TextNormalizer.normalize("打开空调"), okCtx) is FastIntentMatchResult.Unique)
    }

    @Test
    fun `规则冲突返回 Ambiguous 不直达且携带冲突 Tool 集合`() = runTest {
        val ambiguousRegistry = ToolRegistry()
            .register(climateTool("climate.a", listOf("打开空调")))
            .register(climateTool("climate.b", listOf("打开空调")))
        val ambiguousMatcher = DefaultFastIntentMatcher(ambiguousRegistry)
        val result = ambiguousMatcher.match(TextNormalizer.normalize("打开空调"), context)
        assertTrue(result is FastIntentMatchResult.Ambiguous)
        val ambiguous = result as FastIntentMatchResult.Ambiguous
        assertEquals(setOf("climate.a", "climate.b"), ambiguous.candidates.map { it.toolId }.toSet())
        assertEquals(setOf("climate.a", "climate.b"), ambiguous.matchedToolIds)
    }

    @Test
    fun `需要确认的 Tool 不进入 L0 直达`() = runTest {
        val confirmRegistry = ToolRegistry().register(
            climateTool("climate.confirm", listOf("打开空调")).copy(
                policy = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolPolicy(
                    requiresConfirmation = true
                )
            )
        )
        val confirmMatcher = DefaultFastIntentMatcher(confirmRegistry)
        assertTrue(confirmMatcher.match(TextNormalizer.normalize("打开空调"), context) is FastIntentMatchResult.NoMatch)
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
