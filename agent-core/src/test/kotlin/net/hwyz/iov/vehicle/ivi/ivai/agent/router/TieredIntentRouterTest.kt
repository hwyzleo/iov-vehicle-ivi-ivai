package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.RetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-005 分级意图路由：L0 直达 / L1 工具领域 / L2 知识领域 / L3 开放域 / REJECT 安全。
 */
class TieredIntentRouterTest {

    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
    private val router = TieredIntentRouter(
        matcher = DefaultFastIntentMatcher(registry),
        domainClassifier = DomainClassifier(registry)
    )

    private fun route(text: String): TieredRouteDecision = runBlocking {
        val normalized = TextNormalizer.normalize(text)
        val context = AgentContext(
            requestId = "r", sessionId = "s", source = "mock"
        )
        router.route(normalized, context)
    }

    @Test
    fun `打开空调路由到 L0 直达且携带候选`() {
        val decision = route("打开空调")
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, decision.tier)
        assertNotNull(decision.directCandidate)
        assertNull(decision.retrievalQuery)
        assertEquals("climate.power_on", decision.directCandidate!!.toolId)
    }

    @Test
    fun `我有点冷路由到 L1 工具领域`() {
        val decision = route("我有点冷")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, decision.tier)
        assertNull(decision.directCandidate)
        assertTrue(decision.retrievalQuery is RetrievalQuery.Tools)
    }

    @Test
    fun `温度调到缺参路由到 L1 且携带缺参信息`() {
        val decision = route("温度调到")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, decision.tier)
        assertEquals("climate.temperature_set", decision.missingToolId)
        assertTrue(decision.missingArguments.contains("temperature"))
    }

    @Test
    fun `胎压报警是什么意思路由到 L2 知识领域`() {
        val decision = route("胎压报警是什么意思")
        assertEquals(IntentTier.L2_LOCAL_KNOWLEDGE, decision.tier)
        assertTrue(decision.retrievalQuery is RetrievalQuery.Knowledge)
    }

    @Test
    fun `今天天气怎么样路由到 L3 开放域`() {
        val decision = route("今天天气怎么样")
        assertEquals(IntentTier.L3_CLOUD_AI, decision.tier)
    }

    @Test
    fun `驾驶安全请求路由到 REJECT`() {
        val decision = route("帮我把车开走")
        assertEquals(IntentTier.REJECT, decision.tier)
        assertEquals(RouteReasonCode.REJECT_SAFETY, decision.reasonCode)
    }

    @Test
    fun `多意图请求落到 L1 由本地模型消歧`() {
        val decision = route("打开空调然后把温度调到26度")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, decision.tier)
        assertEquals(RouteReasonCode.L0_MULTI_INTENT, decision.reasonCode)
    }

    @Test
    fun `规则冲突落到 L1 并记录 L0 冲突原因`() {
        val decision = route("打开空调降温")
        // 打开+降温 → 多意图 → L1；若未判多意图则命中两个工具（冲突）→ 同样 L1
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, decision.tier)
        assertTrue(
            decision.reasonCode == RouteReasonCode.L0_MULTI_INTENT ||
                decision.reasonCode == RouteReasonCode.L0_RULE_AMBIGUOUS
        )
    }
}
