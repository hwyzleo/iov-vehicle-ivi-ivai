package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-008 验证设计 · 领域预路由 + 能力包过滤集成（TieredIntentRouter CR-008 路径）：
 *  - 「查看空调状态」→ CABIN_COMFORT + QUERY → 包内 L0 直达状态查询 Tool。
 *  - 「打开充电设置」→ ENERGY + NAVIGATE_UI → 无可用包 → NO_AVAILABLE_CAPABILITY（IVAI-CAP-001）。
 *  - 「我有点冷」→ 领域限定 L1（候选只来自选中包）。
 *  - 「打开露营模式」→ WORKFLOW_EXECUTION。
 *  - 低置信 / 知识类回退旧链路（L2/L3）。
 */
class TieredIntentRouterCr008Test {

    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
    private val cr008Router = TieredIntentRouter(
        matcher = DefaultFastIntentMatcher(registry),
        domainClassifier = DomainClassifier(registry),
        domainRouter = DomainRouter(registry),
        capabilitySelector = CapabilityPackSelector(),
        registry = registry
    )

    private fun route(text: String): TieredRouteDecision = runBlocking {
        val normalized = TextNormalizer.normalize(text)
        val context = AgentContext(requestId = "r", sessionId = "s", source = "mock")
        cr008Router.route(normalized, context)
    }

    @Test
    fun `查看空调状态走包内 L0 并携带领域与快照`() {
        val decision = route("查看空调状态")
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, decision.tier)
        assertEquals("climate.status_query", decision.directCandidate!!.toolId)
        assertNotNull(decision.domain)
        assertEquals("BD01", decision.domain!!.topDomain?.code)
        assertEquals("QUERY", decision.domain!!.operationType.name)
        assertNotNull(decision.capabilitySnapshot)
        assertEquals(setOf("cabin.climate"), decision.capabilitySnapshot!!.packs.map { it.packId }.toSet())
    }

    @Test
    fun `打开充电设置识别能源领域但无可用包被拒绝 IVAI-CAP-001`() {
        val decision = route("打开充电设置")
        assertEquals(IntentTier.REJECT, decision.tier)
        assertEquals(RouteReasonCode.DOMAIN_NO_AVAILABLE_PACK, decision.reasonCode)
        assertEquals("BD04", decision.domain!!.topDomain?.code)
        assertEquals("NAVIGATE_UI", decision.domain!!.operationType.name)
        assertTrue(decision.capabilitySnapshot!!.packs.isEmpty())
    }

    @Test
    fun `我有点冷走领域限定 L1 且检索查询携带能力包范围`() {
        val decision = route("我有点冷")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, decision.tier)
        assertNull(decision.directCandidate)
        val tools = decision.retrievalQuery as? net.hwyz.iov.vehicle.ivi.ivai.retrieval.RetrievalQuery.Tools
        assertNotNull(tools)
        assertEquals(listOf("cabin.climate"), tools!!.query.capabilityPackIds)
        assertEquals(listOf("BD01"), tools.query.domainIds.map { it.code })
    }

    @Test
    fun `打开露营模式唯一匹配已注册 Workflow 进入 WORKFLOW_EXECUTION`() {
        val decision = route("打开露营模式")
        assertEquals(IntentTier.WORKFLOW_EXECUTION, decision.tier)
        assertEquals(RouteReasonCode.WORKFLOW_MATCHED, decision.reasonCode)
        assertNotNull(decision.workflow)
        assertEquals("cabin.camping_mode", decision.workflow!!.workflowId)
        assertNotNull(decision.capabilitySnapshot)
        assertTrue(decision.capabilitySnapshot!!.filteredWorkflowIds.contains("cabin.camping_mode"))
    }

    @Test
    fun `知识类问题回退旧链路 L2 知识`() {
        val decision = route("胎压报警是什么意思")
        assertEquals(IntentTier.L2_LOCAL_KNOWLEDGE, decision.tier)
        assertTrue(decision.retrievalQuery is net.hwyz.iov.vehicle.ivi.ivai.retrieval.RetrievalQuery.Knowledge)
    }

    @Test
    fun `开放域回退 L3`() {
        val decision = route("今天天气怎么样")
        // 天气仅在 BD10 单一信号命中 → 低置信 → 旧链路；DomainClassifier 判 OPEN → L3。
        assertEquals(IntentTier.L3_CLOUD_AI, decision.tier)
    }

    @Test
    fun `驾驶安全仍在领域路由前被拒绝`() {
        val decision = route("帮我把车开走")
        assertEquals(IntentTier.REJECT, decision.tier)
        assertEquals(RouteReasonCode.REJECT_SAFETY, decision.reasonCode)
    }

    @Test
    fun `未启用 CR008 时保持旧链路行为`() {
        val legacy = TieredIntentRouter(
            matcher = DefaultFastIntentMatcher(registry),
            domainClassifier = DomainClassifier(registry)
        )
        val decision = runBlocking {
            legacy.route(
                TextNormalizer.normalize("打开空调"),
                AgentContext(requestId = "r", sessionId = "s", source = "mock")
            )
        }
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, decision.tier)
        assertNull(decision.domain)
        assertNull(decision.capabilitySnapshot)
    }
}
