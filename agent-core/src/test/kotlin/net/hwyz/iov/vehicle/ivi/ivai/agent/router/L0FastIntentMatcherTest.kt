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
        assertEquals("L0.climate.power.set.on", unique.matchedPatternId)
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
        assertEquals("L0.climate.power.set.off", unique.matchedPatternId)
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
        val result = match("主驾空调设成25度")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.temperature.set", unique.candidate.toolId)
        assertEquals(25.0, unique.candidate.arguments["temperature"])
    }

    @Test
    fun `温度调高一点唯一匹配 adjust 且 direction=increase`() = runTest {
        val result = match("主驾温度调高一点")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.temperature.adjust", unique.candidate.toolId)
        assertEquals("increase", unique.candidate.arguments["direction"])
    }

    @Test
    fun `温度调低一点匹配 adjust 且 direction=decrease`() = runTest {
        val result = match("主驾温度调低一点")
        assertTrue(result is FastIntentMatchResult.Unique)
        assertEquals("decrease", (result as FastIntentMatchResult.Unique).candidate.arguments["direction"])
    }

    @Test
    fun `调高两度抽取中文数字步进 step=2`() = runTest {
        val result = match("主驾温度调高两度")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.temperature.adjust", unique.candidate.toolId)
        assertEquals(2, unique.candidate.arguments["step"])
        assertEquals("increase", unique.candidate.arguments["direction"])
    }

    @Test
    fun `调高 2 度抽取阿拉伯数字步进 step=2`() = runTest {
        val result = match("主驾温度调高2度")
        assertTrue(result is FastIntentMatchResult.Unique)
        assertEquals(2, (result as FastIntentMatchResult.Unique).candidate.arguments["step"])
    }

    @Test
    fun `调节两档抽取步进 step=2`() = runTest {
        val result = match("主驾温度调高两档")
        assertTrue(result is FastIntentMatchResult.Unique)
        assertEquals(2, (result as FastIntentMatchResult.Unique).candidate.arguments["step"])
    }

    @Test
    fun `相对动词加到绝对数值归入绝对语义不命中 adjust`() = runTest {
        // CR-019：ABSOLUTE_TARGET 语义禁止 temperature.adjust 参与候选；
        // set 规则 exactPhrases 未覆盖“调高到”表达 → L0 NoMatch，交由 L1 检索 set
        //（与 V2 Suite 分组8“主驾温度调高到24度 → set L1”一致）。
        val result = match("主驾温度调高到26度")
        assertTrue(result !is FastIntentMatchResult.Unique, "不得命中 adjust（相对增减）")
        assertTrue(result is FastIntentMatchResult.NoMatch)
    }

    @Test
    fun `打开主驾座椅按摩唯一匹配 seat_massage_set 且 enabled=true`() = runTest {
        val result = match("打开主驾座椅按摩")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("seat.massage.set", unique.candidate.toolId)
        assertEquals(true, unique.candidate.arguments["enabled"])
    }

    @Test
    fun `打开座椅按摩缺 position 返回 MissingArguments`() = runTest {
        // CR-013：seat.massage.set 的 position 为必填槽位，未指明位置 → 缺参进入 L1/追问。
        val result = match("打开座椅按摩")
        assertTrue(result is FastIntentMatchResult.MissingArguments)
        val missing = result as FastIntentMatchResult.MissingArguments
        assertEquals("seat.massage.set", missing.toolId)
        assertTrue(missing.missing.contains("position"))
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
        assertEquals("L0.climate.temperature.adjust.driver_up", unique.matchedPatternId)
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
    fun `温度语义歧义返回 NeedsDialogue`() = runTest {
        // CR-019：“温度调到”缺少单位与目标值 → AMBIGUOUS → NEED_DIALOGUE
        // （IVAI-TEMP-SEMANTIC-001），不再是 MissingArguments 缺参直达。
        val result = match("温度调到")
        assertTrue(result is FastIntentMatchResult.NeedsDialogue)
        val dialogue = result as FastIntentMatchResult.NeedsDialogue
        assertEquals("IVAI-TEMP-SEMANTIC-001", dialogue.reasonCode)
    }

    @Test
    fun `非温度缺参仍返回 MissingArguments`() = runTest {
        // CR-019：非温度请求（无温度语义证据）保持原有缺参快路径。
        val result = match("风量档位调到")
        assertTrue(result is FastIntentMatchResult.MissingArguments)
        val missing = result as FastIntentMatchResult.MissingArguments
        assertEquals("climate.fan.speed.set", missing.toolId)
    }

    @Test
    fun `温度越界直接拒绝`() = runTest {
        val result = match("温度调到35度")
        assertTrue(result is FastIntentMatchResult.Rejected)
        val rejected = result as FastIntentMatchResult.Rejected
        assertEquals("IVAI-TEMP-RANGE-001", rejected.reasonCode)
    }

    @Test
    fun `相对调温不命中 temperature_set`() = runTest {
        // CR-019：RELATIVE_DELTA 语义禁止 temperature.set 参与候选（adjust/set 混淆防护）。
        val result = match("主驾温度调高1度")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("climate.temperature.adjust", unique.candidate.toolId)
        assertEquals("increase", unique.candidate.arguments["direction"])
        assertEquals(1, unique.candidate.arguments["step"])
        assertEquals("driver", unique.candidate.arguments["zone"])
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

    // ---------------------------------------------------------------- CR-013

    @Test
    fun `我不想打开空调全局否定不产生 L0 候选`() = runTest {
        val result = match("我不想打开空调")
        assertTrue(result is FastIntentMatchResult.NoMatch, "全局否定必须拦截，不得因包含“打开空调”子串产生 L0")
    }

    @Test
    fun `打开空调设置页面是导航意图不得命中空调电源控制`() = runTest {
        val result = match("打开空调设置页面")
        assertFalse(result is FastIntentMatchResult.Unique, "NAVIGATE_UI 表达不得命中空调电源控制")
    }

    @Test
    fun `为什么前挡除雾是知识问题不触发除霜车控`() = runTest {
        val result = match("为什么前挡除雾时通常要让风吹向玻璃")
        assertFalse(result is FastIntentMatchResult.Unique, "知识问题不得触发除霜控制 L0")
    }

    @Test
    fun `NOT_SUPPORTED Tool 无规则不产生 L0 候选`() = runTest {
        // media.playback.play 为 NOT_SUPPORTED，不得有规则/不得命中 L0。
        assertTrue(match("播放媒体") is FastIntentMatchResult.NoMatch)
    }

    @Test
    fun `NEEDS_REVIEW Tool 无规则不产生 L0 候选`() = runTest {
        // vehicle.drive_mode.set 为 NEEDS_REVIEW（词表未闭合），不生成生产规则。
        assertTrue(match("设置驾驶模式") is FastIntentMatchResult.NoMatch)
    }

    @Test
    fun `显式槽位与规则预置矛盾返回 ArgumentConflict IVAI-ROUTE-005`() = runTest {
        // 构造：规则预置 zone=driver，但文本显式“副驾” → 矛盾，不得静默决胜。
        val conflictTool = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition(
            toolId = "climate.conflict.test",
            functionId = null,
            name = "矛盾测试",
            description = "test",
            positiveExamples = listOf(),
            negativeExamples = listOf(),
            selectionPriority = 1,
            parameterSchema = """{"type":"object","properties":{"zone":{"type":"string","enum":["driver","passenger"]}},"required":[]}""",
            policy = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolPolicy(),
            execution = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolExecutionBinding(
                adapterId = "test", methodId = "test"
            ),
            deterministicRules = listOf(
                net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule(
                    ruleId = "L0.conflict",
                    toolId = "climate.conflict.test",
                    exactPhrases = listOf("打开空调"),
                    slotPatterns = listOf(
                        net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotPattern(
                            name = "zone",
                            type = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotType.POSITION,
                            required = false,
                            aliases = mapOf("副驾" to "passenger")
                        )
                    ),
                    presetArguments = mapOf("zone" to "driver")
                )
            )
        )
        val conflictMatcher = DefaultFastIntentMatcher(ToolRegistry().register(conflictTool))
        val result = conflictMatcher.match(TextNormalizer.normalize("副驾打开空调"), context)
        assertTrue(result is FastIntentMatchResult.ArgumentConflict, "显式槽位与预置矛盾应返回 ArgumentConflict")
        val conflict = result as FastIntentMatchResult.ArgumentConflict
        assertEquals("zone", conflict.conflictingArgument)
        // CR-016：位置词经版本化 Alias Lexicon 映射 → alias_mapping 来源。
        assertEquals("alias_mapping", conflict.sources["zone"])
    }

    @Test
    fun `L0 唯一命中暴露 matchedRuleIds 与 argumentSources 可观测性`() = runTest {
        val result = match("主驾打开空调")
        assertTrue(result is FastIntentMatchResult.Unique)
        val unique = result as FastIntentMatchResult.Unique
        assertEquals("L0.climate.power.set.on", unique.matchedPatternId)
        assertTrue(unique.matchedRuleIds.contains("L0.climate.power.set.on"))
        assertEquals("driver", unique.candidate.arguments["zone"])
        // CR-016：位置词经版本化 Alias Lexicon 映射 → alias_mapping 来源。
        assertEquals("alias_mapping", unique.argumentSources["zone"])
        assertEquals("rule_preset", unique.argumentSources["enabled"])
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
