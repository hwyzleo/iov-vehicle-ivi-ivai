package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-012 分层路由可区分性：治理配置（AgentService 同款 domainRouter + 统一候选集）
 * 下，L0 未命中后按 规划→L3 / 知识→L2 / 工具→L1 / 开放→L3 分层，使 L2/L3 层级
 * 可被路由区分（执行层仍为预留桩）。用例来源：CR-012 分层测试用例候选 v3。
 */
class TieredIntentRouterCr012Test {

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

    // ---------------------------------------------------------------- 能量回收（BD03）

    @Test
    fun `关闭能量回收 - 无 L0 规则时走工具领域 L1 且命中 BD03 与能力包`() {
        val d = route("关闭能量回收")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, d.tier)
        assertEquals("BD03", d.domain?.topDomain?.code)
        assertTrue(d.capabilitySnapshot?.selectedPackIds?.contains("vehicle.driving_config") == true)
        assertNull(d.directCandidate)
    }

    @Test
    fun `能量回收知识问题 - 提到能力词仍判知识 L2`() {
        val d = route("能量回收强度高低有什么区别？")
        assertEquals(IntentTier.L2_LOCAL_KNOWLEDGE, d.tier)
        assertTrue(d.retrievalQuery is net.hwyz.iov.vehicle.ivi.ivai.retrieval.RetrievalQuery.Knowledge)
        assertEquals("BD03", d.domain?.topDomain?.code)
    }

    @Test
    fun `能量回收规划请求 - 规划动词走 L3`() {
        val d = route("结合当前电量、道路坡度和实时路况，推荐最适合的能量回收设置，但先不要执行")
        assertEquals(IntentTier.L3_CLOUD_AI, d.tier)
    }

    @Test
    fun `隐式能量回收表达走 L1`() {
        val d = route("松开电门后别拖得那么厉害")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, d.tier)
        assertEquals("BD03", d.domain?.topDomain?.code)
    }

    // ---------------------------------------------------------------- 音乐（BD08）

    @Test
    fun `重新播放音乐 - 无 L0 规则走 L1 且命中 BD08`() {
        val d = route("歌曲替我从头播放")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, d.tier)
        assertEquals("BD08", d.domain?.topDomain?.code)
        assertTrue(d.capabilitySnapshot?.selectedPackIds?.contains("media.audio") == true)
    }

    @Test
    fun `音乐知识问题走 L2`() {
        val d = route("单曲循环和列表循环有什么区别？")
        assertEquals(IntentTier.L2_LOCAL_KNOWLEDGE, d.tier)
        assertEquals("BD08", d.domain?.topDomain?.code)
    }

    @Test
    fun `音乐推荐请求走 L3`() {
        val d = route("根据上海现在的天气和实时路况，推荐并播放一首适合通勤路上的歌")
        assertEquals(IntentTier.L3_CLOUD_AI, d.tier)
        assertEquals("BD08", d.domain?.topDomain?.code)
    }

    // ---------------------------------------------------------------- 空调风向（BD01）

    @Test
    fun `吹玻璃吹脚 - 无 L0 规则走 L1 且命中 BD01`() {
        val d = route("帮我把车内吹玻璃吹脚打开")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, d.tier)
        assertEquals("BD01", d.domain?.topDomain?.code)
        assertTrue(d.capabilitySnapshot?.selectedPackIds?.contains("cabin.climate") == true)
    }

    @Test
    fun `除雾知识问题走 L2`() {
        val d = route("为什么前挡除雾时通常要让风吹向玻璃？")
        assertEquals(IntentTier.L2_LOCAL_KNOWLEDGE, d.tier)
        assertEquals("BD01", d.domain?.topDomain?.code)
    }

    @Test
    fun `风向规划请求走 L3`() {
        val d = route("根据车外实时温度、湿度和空气质量，规划接下来一小时最舒适的空调风向")
        assertEquals(IntentTier.L3_CLOUD_AI, d.tier)
        assertEquals("BD01", d.domain?.topDomain?.code)
    }

    // ---------------------------------------------------------------- REJECT 可达性（当前实现）

    @Test
    fun `同时型冲突表达当前为多意图 L1 而非 REJECT（REJECT 用例需另设计）`() {
        val d = route("关闭能量回收，同时把能量回收调到最强")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, d.tier)
        assertEquals(RouteReasonCode.L0_MULTI_INTENT, d.reasonCode)
    }

    @Test
    fun `驾驶安全关键词仍先于一切被拒绝`() {
        val d = route("帮我把车开走")
        assertEquals(IntentTier.REJECT, d.tier)
        assertEquals(RouteReasonCode.REJECT_SAFETY, d.reasonCode)
    }
}
