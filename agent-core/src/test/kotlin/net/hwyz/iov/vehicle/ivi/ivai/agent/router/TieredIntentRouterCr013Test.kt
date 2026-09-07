package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-013 验证设计 · L0 确定性资格 / 结构化规则契约 / 全局冲突解析集成（EARS）。
 *
 *  - 160 项全量资格评审：仅 SUPPORTED + 规则有效的 Tool 进入 Matcher 确定性候选；
 *  - “打开座椅按摩”参数完整唯一 → L0（EARS #4）；
 *  - “帮我放松一下腰背”歧义 → L1/追问（EARS #5）；
 *  - “我不想打开空调”全局否定 → 无 L0（EARS #6）；
 *  - “打开空调设置页面”NAVIGATE_UI 区分 → 不得执行空调电源（EARS #7）；
 *  - NOT_SUPPORTED Tool 保留 L1 合法候选，不记确定性覆盖缺口（REQ-125/131）；
 *  - L0 候选继续走安全执行链路，可观测性携带 CR-013 字段。
 */
class TieredIntentRouterCr013Test {

    private val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
    private val router = TieredIntentRouter(
        matcher = DefaultFastIntentMatcher(registry),
        domainClassifier = DomainClassifier(registry),
        domainRouter = DomainRouter(registry),
        capabilitySelector = CapabilityPackSelector.governed(
            catalog = GovernanceWorkspace.runtimePacks(),
            governanceCatalog = GovernanceWorkspace.catalog,
            defaultEnvironment = GovernanceWorkspace.devStubEnvironment()
        ),
        registry = registry
    )

    private fun route(text: String): TieredRouteDecision = runBlocking {
        val normalized = TextNormalizer.normalize(text)
        val context = AgentContext(requestId = "r", sessionId = "s", source = "mock")
        router.route(normalized, context)
    }

    @Test
    fun `打开主驾座椅按摩参数完整且唯一产生 L0 候选`() {
        val d = route("打开主驾座椅按摩")
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, d.tier)
        assertEquals("seat.massage.set", d.directCandidate!!.toolId)
        assertEquals(true, d.directCandidate.arguments["enabled"])
        assertNull(d.retrievalQuery, "L0 不触发 Retrieval/LLM")
    }

    @Test
    fun `打开座椅按摩未指明位置走 L1 追问`() {
        // EARS #5 相关：参数不完整 → 不得 L0。
        val d = route("打开座椅按摩")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, d.tier)
        assertNull(d.directCandidate)
    }

    @Test
    fun `帮我放松一下腰背因歧义进入 L1 不直达`() {
        val d = route("帮我放松一下腰背")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, d.tier)
        assertNull(d.directCandidate)
    }

    @Test
    fun `我不想打开空调全局否定不产生 L0`() {
        val d = route("我不想打开空调")
        assertFalse(d.tier == IntentTier.L0_DETERMINISTIC_TOOL, "全局否定不得产生 L0 候选")
        assertNull(d.directCandidate)
    }

    @Test
    fun `打开空调设置页面区分导航意图不执行空调电源控制`() {
        val d = route("打开空调设置页面")
        assertFalse(d.tier == IntentTier.L0_DETERMINISTIC_TOOL, "NAVIGATE_UI 表达不得执行车控")
        assertNull(d.directCandidate)
    }

    @Test
    fun `为什么前挡除雾知识问题不触发除霜车控`() {
        val d = route("为什么前挡除雾时通常要让风吹向玻璃")
        assertFalse(d.tier == IntentTier.L0_DETERMINISTIC_TOOL, "知识问题不得触发除霜控制 L0")
        assertNull(d.directCandidate)
    }

    @Test
    fun `NOT_SUPPORTED Tool 保留在 L1 合法候选且不记覆盖缺口`() {
        // media.playback.play 为 NOT_SUPPORTED：在 runtimeCandidateToolIds，
        // 不在 deterministicCandidateToolIds；进入 L1 不记录 DETERMINISTIC_COVERAGE_MISSING。
        val d = route("播放媒体")
        val snapshot = d.capabilitySnapshot!!
        assertTrue("media.playback.play" in snapshot.runtimeCandidateToolIds, "NOT_SUPPORTED 必须保留 L1 合法候选")
        assertFalse("media.playback.play" in snapshot.deterministicCandidateToolIds)
        assertNull(d.observability?.deterministicFallbackReason, "NOT_SUPPORTED 正常走 L1 不记缺口")
    }

    @Test
    fun `确定性候选集是运行时候选的 SUPPORTED 投影`() {
        val d = route("打开空调")
        val snapshot = d.capabilitySnapshot!!
        assertTrue(snapshot.deterministicCandidateToolIds.isNotEmpty())
        assertTrue(snapshot.deterministicCandidateToolIds.all { it in snapshot.runtimeCandidateToolIds })
        assertEquals("ivai-l0-rules-v1-draft", snapshot.deterministicCatalogVersion)
        assertNotNull(snapshot.deterministicCatalogHash)
        // 确定性候选是治理 Profile 投影：全部在全局 productionToolIds（102 个 SUPPORTED）内。
        val production = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance
            .DeterministicIntentCatalog.build().productionToolIds
        assertTrue(snapshot.deterministicCandidateToolIds.all { it in production })
    }

    @Test
    fun `L0 路由可观测性携带 CR-013 字段`() {
        val d = route("打开空调")
        val obs = d.observability!!
        assertEquals("SUPPORTED", obs.deterministicSupport)
        assertEquals("ivai-l0-rules-v1-draft", obs.ruleVersion)
        assertTrue(obs.matchedRuleIds.contains("L0.climate.power.set.on"))
        assertTrue(obs.deterministicCandidateCount!! > 0)
        assertTrue(obs.canonicalCandidateCount!! >= obs.deterministicCandidateCount!!)
        assertEquals("rule_preset", obs.argumentSources["enabled"])
        assertNotNull(obs.deterministicCatalogHash)
    }

    @Test
    fun `L0 命中规则在确定性候选集内且 NOT_SUPPORTED 不在其中`() {
        // 车辆设置包：SUPPORTED（陡坡缓降）在确定性候选；NEEDS_REVIEW（驾驶模式）不在其中但仍保留 L1。
        val snapshot = route("打开陡坡缓降").capabilitySnapshot!!
        assertFalse("vehicle.drive_mode.set" in snapshot.deterministicCandidateToolIds)
        assertTrue("vehicle.drive_mode.set" in snapshot.runtimeCandidateToolIds)
        assertTrue("vehicle.hill_descent.set" in snapshot.deterministicCandidateToolIds)
        // 空调域：SUPPORTED 示例在确定性候选内。
        val climateSnapshot = route("打开空调").capabilitySnapshot!!
        assertTrue("climate.power.set" in climateSnapshot.deterministicCandidateToolIds)
        assertTrue("body.window.set" !in climateSnapshot.runtimeCandidateToolIds, "未选中 Pack 的 Tool 不在候选集")
    }
}
