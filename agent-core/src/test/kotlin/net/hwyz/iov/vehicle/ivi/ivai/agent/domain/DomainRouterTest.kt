package net.hwyz.iov.vehicle.ivi.ivai.agent.domain

import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.AgentContext
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TextNormalizer
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.SemanticFeature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-008 验证设计 · Domain Router 单元测试：
 *  - 业务领域与操作类型拆分（对象/动作分离）。
 *  - Top-N 领域、歧义、否定、多意图、隐式表达语义特征。
 *  - 「查看空调状态」→ 座舱舒适 + QUERY；「打开充电设置」→ 能源与补能 + NAVIGATE_UI。
 */
class DomainRouterTest {

    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
    private val router = DomainRouter(registry)

    private fun route(text: String): DomainRouteDecision {
        val input = TextNormalizer.normalize(text)
        val context = AgentContext(requestId = "r", sessionId = "s", source = "mock")
        return router.route(input, context)
    }

    @Test
    fun `查看空调状态识别为座舱舒适与 QUERY 操作类型`() {
        val decision = route("查看空调状态")
        assertTrue(decision.classified)
        assertEquals(BusinessDomainId.CABIN_COMFORT, decision.topDomain)
        assertEquals(OperationType.QUERY, decision.operationType)
        assertEquals(DomainReasonCode.DOMAIN_CONFIDENT, decision.reasonCode)
    }

    @Test
    fun `打开充电设置识别为能源与补能与 NAVIGATE_UI`() {
        val decision = route("打开充电设置")
        assertTrue(decision.classified)
        assertEquals(BusinessDomainId.ENERGY, decision.topDomain)
        assertEquals(OperationType.NAVIGATE_UI, decision.operationType)
        assertTrue(decision.candidates.size <= 3)
    }

    @Test
    fun `打开空调识别为座舱舒适与 CONTROL`() {
        val decision = route("打开空调")
        assertTrue(decision.classified)
        assertEquals(BusinessDomainId.CABIN_COMFORT, decision.topDomain)
        assertEquals(OperationType.CONTROL, decision.operationType)
    }

    @Test
    fun `主驾升高两度识别为座舱舒适并标记显式语义`() {
        val decision = route("主驾升高两度")
        assertTrue(decision.classified)
        assertEquals(BusinessDomainId.CABIN_COMFORT, decision.topDomain)
        assertEquals(OperationType.CONTROL, decision.operationType)
    }

    @Test
    fun `我有点冷保留隐式表达语义特征并路由座舱舒适`() {
        val decision = route("我有点冷")
        assertTrue(decision.classified)
        assertEquals(BusinessDomainId.CABIN_COMFORT, decision.topDomain)
        assertTrue(decision.semanticFeatures.contains(SemanticFeature.IMPLICIT_EXPRESSION))
    }

    @Test
    fun `否定与多意图被标记为语义特征`() {
        val negated = route("别打开空调")
        assertTrue(negated.semanticFeatures.contains(SemanticFeature.NEGATION))

        val multi = route("打开空调然后把温度调到26度")
        assertTrue(multi.semanticFeatures.contains(SemanticFeature.MULTI_INTENT))
    }

    @Test
    fun `知识类与开放域请求不误判为业务领域`() {
        val knowledge = route("胎压报警是什么意思")
        // 胎压/报警不在 BD01~BD10 词表 → 低置信/未分类。
        assertFalse(knowledge.classified)
        assertEquals(DomainAmbiguity.LOW_CONFIDENCE, knowledge.ambiguity)

        val open = route("今天天气怎么样")
        // 天气在 BD10 信息服务词表，但置信度低（单一信号）。
        assertTrue(open.candidates.size <= 3)
    }

    @Test
    fun `业务领域候选带得分与命中信号`() {
        val decision = route("打开露营模式")
        assertTrue(decision.classified)
        val top = decision.candidates.first()
        assertEquals(BusinessDomainId.CABIN_COMFORT, top.domainId)
        assertTrue(top.score >= 1.0)
        assertTrue(top.matchedSignals.isNotEmpty())
    }

    @Test
    fun `操作类型分类覆盖常用动词`() = runBlocking {
        assertEquals(OperationType.NAVIGATE_UI, DomainRouter.OperationClassifier.classify("打开充电设置"))
        assertEquals(OperationType.QUERY, DomainRouter.OperationClassifier.classify("查看空调状态"))
        assertEquals(OperationType.CONTROL, DomainRouter.OperationClassifier.classify("打开空调"))
        assertEquals(OperationType.WORKFLOW, DomainRouter.OperationClassifier.classify("打开露营模式"))
        assertEquals(OperationType.PLAYBACK, DomainRouter.OperationClassifier.classify("播放一首歌"))
        assertEquals(OperationType.SEARCH, DomainRouter.OperationClassifier.classify("搜索附近的充电站"))
    }
}
