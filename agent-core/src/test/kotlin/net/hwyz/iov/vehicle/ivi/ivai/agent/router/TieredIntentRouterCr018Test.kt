package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.CabinAirflowSemanticLexicon
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.SemanticObject
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-018 验证设计 · 座舱气流领域路由与本地能力保护：
 *  - 出风/吹风/送风/风口/风量/风速/气流 → CABIN_COMFORT 证据；
 *  - 气流词未形成 CABIN_COMFORT 证据 → IVAI-DOMAIN-AIRFLOW-001（airflowEvidenceFailed）；
 *  - CABIN_COMFORT 证据 + cabin.climate 候选时未命中 L0 必须走 L1，禁止无原因 L3。
 */
class TieredIntentRouterCr018Test {

    private val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
    private val environment = GovernanceWorkspace.devStubEnvironment()
    private val router = TieredIntentRouter(
        matcher = DefaultFastIntentMatcher(registry),
        domainClassifier = DomainClassifier(registry),
        domainRouter = DomainRouter(registry),
        capabilitySelector = CapabilityPackSelector.governed(
            catalog = GovernanceWorkspace.runtimePacks(),
            governanceCatalog = GovernanceWorkspace.catalog,
            defaultEnvironment = environment
        ),
        registry = registry
    )

    private fun route(text: String): TieredRouteDecision = runBlocking {
        val normalized = TextNormalizer.normalize(text)
        val context = AgentContext(requestId = "r", sessionId = "s", source = "mock", softwareVersion = "0.1.0")
        router.route(normalized, context)
    }

    @Test
    fun `开出风进入 CABIN_COMFORT 且形成 VENT 对象证据`() {
        val d = route("开出风")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, d.tier)
        assertEquals("BD01", d.domain?.topDomain?.code)
        assertTrue(SemanticObject.VENT in (d.domain?.airflowObjects ?: emptySet()))
        assertTrue(d.capabilitySnapshot?.selectedPackIds?.contains("cabin.climate") == true)
    }

    @Test
    fun `送风与气流表达均路由 CABIN_COMFORT 而非 L3`() {
        for (text in listOf("开送风", "把气流调大", "打开风口", "开通风口")) {
            val d = route(text)
            assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, d.tier, "输入应走 L1: $text")
            assertEquals("BD01", d.domain?.topDomain?.code)
        }
    }

    @Test
    fun `吹风不命中 L0 时进入 L1 而非 L3`() {
        val d = route("开吹风")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, d.tier)
        assertEquals("BD01", d.domain?.topDomain?.code)
        assertNull(d.directCandidate)
    }

    @Test
    fun `自动空调未命中唯一 L0 时进入 L1 消歧`() {
        val d = route("开自动空调")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, d.tier)
        assertTrue(SemanticObject.AUTO_HVAC in (d.domain?.airflowObjects ?: emptySet()))
    }

    @Test
    fun `规划请求即使含气流词仍走 L3`() {
        val d = route("根据实时温度规划空调出风方案")
        assertEquals(IntentTier.L3_CLOUD_AI, d.tier)
    }

    @Test
    fun `知识请求即使含气流词仍走 L2`() {
        val d = route("空调出风模式是什么意思")
        assertEquals(IntentTier.L2_LOCAL_KNOWLEDGE, d.tier)
    }

    @Test
    fun `非气流开放请求保持 L3`() {
        val d = route("帮我写一首诗")
        assertEquals(IntentTier.L3_CLOUD_AI, d.tier)
    }

    @Test
    fun `气流对象未命中 L0 且存在本地候选时强制 L1 而非 L3`() {
        // CR-018：CABIN_COMFORT 证据 + cabin.climate 候选 → 未命中 L0 必须 L1。
        for (text in listOf("开出风", "开吹风", "开送风", "把气流调大", "打开风口", "开通风口")) {
            val d = route(text)
            assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, d.tier, "输入应走 L1: $text")
            assertEquals("BD01", d.domain?.topDomain?.code)
            assertNull(d.directCandidate)
        }
    }
}
