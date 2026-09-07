package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-010 验证设计 · 统一候选集 + 分层路由集成。
 *
 * L0/L1 是请求解析路径而非 Tool 分类；同一 Tool 可被明确表达走 L0、隐式表达走
 * L1。全部运行时可执行 Tool 进入同一个 runtimeCandidateToolIds（无独立 L0
 * 白名单），L1 只能在该集合或其 Top-K 子集内选择。装配与 AgentService 共用同一
 * RuntimeCapabilityAssembler（EARS #10）。
 */
class TieredIntentRouterCr010Test {

    private val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
    private val cr010Router = TieredIntentRouter(
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
        cr010Router.route(normalized, context)
    }

    private fun assertL0(decision: TieredRouteDecision, toolId: String) {
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, decision.tier)
        assertEquals(toolId, decision.directCandidate!!.toolId)
    }

    @Test
    fun `打开空调唯一匹配 canonical power_set 走 L0 且不创建模型请求`() {
        val decision = route("打开空调")
        assertL0(decision, "climate.power.set")
        assertEquals(true, decision.directCandidate!!.arguments["enabled"])
        assertNull(decision.retrievalQuery, "L0 不触发 Retrieval")
        // 可观测性
        assertEquals("climate.power.set", decision.observability?.canonicalToolId)
        assertEquals("L0.climate.power.set.on", decision.observability?.matchedPatternId)
        assertNotNull(decision.observability?.runtimeCandidateToolIdsHash)
        assertEquals("DEVELOPMENT_STUB", decision.observability?.governanceRuntimeMode)
        assertTrue(decision.observability?.selectedPackIds?.contains("cabin.climate") == true)
    }

    @Test
    fun `打开主驾座椅按摩唯一匹配 seat_massage_set 走 L0`() {
        val decision = route("打开主驾座椅按摩")
        assertL0(decision, "seat.massage.set")
        assertEquals(true, decision.directCandidate!!.arguments["enabled"])
        assertNull(decision.retrievalQuery)
    }

    @Test
    fun `打开座椅按摩缺 position 走 L1 追问`() {
        // CR-013：position 为必填槽位，未指明位置 → 缺参进入 L1/追问。
        val decision = route("打开座椅按摩")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, decision.tier)
        assertNull(decision.directCandidate)
    }

    @Test
    fun `查看空调状态走 L0 状态查询`() {
        val decision = route("查看空调状态")
        assertL0(decision, "climate.status.query")
    }

    @Test
    fun `我有点冷走 L1 消歧`() {
        val decision = route("我有点冷")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, decision.tier)
        assertNull(decision.directCandidate)
        assertTrue(decision.retrievalQuery is net.hwyz.iov.vehicle.ivi.ivai.retrieval.RetrievalQuery.Tools)
        // 隐式表达不属于覆盖缺口。
        assertNull(decision.observability?.deterministicFallbackReason)
    }

    @Test
    fun `帮我舒服一点走 L1`() {
        val decision = route("帮我舒服一点")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, decision.tier)
        assertNull(decision.directCandidate)
    }

    @Test
    fun `同一 Tool 明确表达走 L0 隐式表达走 L1 不固化层级`() {
        // 明确表达 → L0（climate.temperature.adjust，需指明位置以补全必填 zone）
        assertL0(route("主驾温度调高一点"), "climate.temperature.adjust")
        // 隐式表达 → L1（最终仍可能选择同一 Tool，但层级不同）
        val implicit = route("我有点冷")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, implicit.tier)
    }

    @Test
    fun `明确表达缺少确定性元数据记录覆盖缺口 IVAI-ROUTE-004 但仍走 L1`() {
        val decision = route("打开风量设置")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, decision.tier)
        assertEquals("DETERMINISTIC_COVERAGE_MISSING", decision.observability?.deterministicFallbackReason)
        assertEquals("IVAI-ROUTE-004", decision.observability?.errorCode)
    }

    @Test
    fun `L1 检索查询只携带选中 Pack 与统一候选集内 Tool`() {
        val decision = route("我有点冷")
        val tools = decision.retrievalQuery as net.hwyz.iov.vehicle.ivi.ivai.retrieval.RetrievalQuery.Tools
        assertTrue(tools.query.capabilityPackIds.contains("cabin.climate"))
        // L1 候选只在统一候选集内（装配输出）。
        val snapshot = decision.capabilitySnapshot!!
        assertTrue(snapshot.runtimeCandidateToolIds.isNotEmpty())
        assertEquals(snapshot.runtimeCandidateToolIds, snapshot.filteredToolIds)
    }

    @Test
    fun `统一候选集包含全部运行时可执行 Tool 无独立 L0 白名单`() {
        val decision = route("查看空调状态")
        val snapshot = decision.capabilitySnapshot!!
        // 空调 + 座椅舒适等 P0 Pack 的 Tool 均进入同一候选集（STUB 全部放行）。
        assertTrue("climate.power.set" in snapshot.runtimeCandidateToolIds)
        assertTrue("climate.status.query" in snapshot.runtimeCandidateToolIds)
        assertTrue("seat.massage.set" in snapshot.runtimeCandidateToolIds)
        // 旧空调 ID 不在统一候选集（已 canonicalize 到治理 Tool ID）。
        assertTrue("climate.power_on" !in snapshot.runtimeCandidateToolIds)
    }
}
