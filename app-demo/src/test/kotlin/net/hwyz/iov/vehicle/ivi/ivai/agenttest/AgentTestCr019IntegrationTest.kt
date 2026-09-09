package net.hwyz.iov.vehicle.ivi.ivai.agenttest

import java.io.File
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.AgentContext
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DefaultFastIntentMatcher
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DomainClassifier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.RouteReasonCode
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TextNormalizer
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TieredIntentRouter
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.AgentTestSuiteParser
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.AgentTestSuiteValidator
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestSuite
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ExpectedOutcome
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-019 集成测试：温度 V2 验证 Suite（schemaVersion=2）全链路兼容 + 代表性语义路由。
 *
 *  - Importer：AgentTestSuiteParser 解析 schemaVersion=2（含 expectedOutcome/reasonCode）；
 *  - Validator：200 条全部通过（EXECUTE 必须有 Target，NEED_DIALOGUE/REJECTED 无可执行 Target）；
 *  - 分布断言：Outcome EXECUTE 161 / NEED_DIALOGUE 24 / REJECTED 15；
 *    Target adjust 95 / set 66；Tier L0 55 / L1 145；
 *  - 代表性路由（真实 GovernanceWorkspace + TieredIntentRouter）：
 *    L0 adjust 相对调温 / set 绝对目标 / 越界 REJECT / 歧义 L1（NEED_DIALOGUE）。
 */
class AgentTestCr019IntegrationTest {

    private fun loadSuite(): AgentTestSuite {
        val file = File("src/debug/assets/agent-tests/v2/temperature-boundary-v2.json")
        assertTrue(file.exists(), "V2 温度套件资产必须存在: ${file.absolutePath}")
        return AgentTestSuiteParser().parse(file.readText())
    }

    private fun route(text: String): net.hwyz.iov.vehicle.ivi.ivai.agent.router.TieredRouteDecision {
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        val tieredRouter = TieredIntentRouter(
            matcher = DefaultFastIntentMatcher(registry),
            domainClassifier = DomainClassifier(registry),
            domainRouter = DomainRouter(registry),
            capabilitySelector = net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector.governed(
                catalog = GovernanceWorkspace.runtimePacks(),
                governanceCatalog = GovernanceWorkspace.catalog,
                defaultEnvironment = GovernanceWorkspace.devStubEnvironment()
            ),
            registry = registry
        )
        return runBlocking {
            val normalized = TextNormalizer.normalize(text)
            val context = AgentContext(requestId = "r", sessionId = "s", source = "mock", vehicleModel = "demo")
            tieredRouter.route(normalized, context)
        }
    }

    @Test
    fun `V2 温度套件解析校验并满足分布断言`() {
        val suite = loadSuite()
        assertEquals("ivai-agent-climate-temperature-boundary-regression-v2", suite.suiteId)
        assertEquals(2, suite.schemaVersion, "V2 Suite 必须 schemaVersion=2")
        assertEquals(200, suite.cases.size)

        val validation = AgentTestSuiteValidator().validate(suite)
        assertTrue(validation.valid, "V2 套件全量校验必须通过: ${validation.fatalErrorMessage} ${validation.caseIssues}")

        val outcomes = suite.cases.groupingBy { it.expectedOutcome }.eachCount()
        assertEquals(161, outcomes[ExpectedOutcome.EXECUTE])
        assertEquals(24, outcomes[ExpectedOutcome.NEED_DIALOGUE])
        assertEquals(15, outcomes[ExpectedOutcome.REJECTED])

        val byTool = suite.cases.groupingBy { it.expectedTarget?.id }.eachCount()
        assertEquals(95, byTool["climate.temperature.adjust"])
        assertEquals(66, byTool["climate.temperature.set"])

        val tiers = suite.cases.groupingBy { it.expectedTier }.eachCount()
        assertEquals(55, tiers[IntentTier.L0_DETERMINISTIC_TOOL])
        assertEquals(145, tiers[IntentTier.L1_LOCAL_TOOL_REASONING])
    }

    @Test
    fun `V2 用例契约合法`() {
        val suite = loadSuite()
        // EXECUTE 必须有 Target；NEED_DIALOGUE/REJECTED 必须无可执行 Target 且带 reasonCode。
        suite.cases.forEach { c ->
            when (c.expectedOutcome) {
                ExpectedOutcome.EXECUTE -> assertTrue(c.expectedTarget != null, "EXECUTE 缺 Target: ${c.caseId}")
                ExpectedOutcome.NEED_DIALOGUE,
                ExpectedOutcome.REJECTED -> {
                    assertTrue(c.expectedTarget == null, "${c.expectedOutcome} 不应有 Target: ${c.caseId}")
                    assertTrue(!c.expectedReasonCode.isNullOrBlank(), "${c.expectedOutcome} 缺 reasonCode: ${c.caseId}")
                }
                null -> {}
            }
        }
    }

    @Test
    fun `相对调温路由到 L0 adjust`() {
        val decision = route("主驾温度调高1度")
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, decision.tier)
        val candidate = decision.directCandidate
        assertEquals("climate.temperature.adjust", candidate!!.toolId)
        assertEquals("driver", candidate.arguments["zone"])
        assertEquals("increase", candidate.arguments["direction"])
    }

    @Test
    fun `合法绝对目标路由到 set`() {
        val decision = route("主驾温度调到24度")
        val candidate = decision.directCandidate
        assertEquals("climate.temperature.set", candidate!!.toolId)
        assertEquals(24.0, candidate.arguments["temperature"])
    }

    @Test
    fun `越界绝对温度路由到 L1 且记录越界码`() {
        // 越界绝对温度：处理路径 L1（IVAI-TEMP-RANGE-001），业务终态 REJECTED。
        val decision = route("温度调到35度")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, decision.tier)
        assertEquals("IVAI-TEMP-RANGE-001", decision.reasonCode)
    }

    @Test
    fun `歧义请求路由到 L1 且记录语义码`() {
        val decision = route("温度调到")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, decision.tier)
        assertEquals("IVAI-TEMP-SEMANTIC-001", decision.reasonCode)
    }

    @Test
    fun `再加单一谓词非多意图且正常路由`() {
        // CR-019 验收点：“再+单一温度谓词”不得判为 MULTI_INTENT。
        val normalized = TextNormalizer.normalize("主驾温度再调高1度")
        assertTrue(!normalized.hasMultiIntent, "“再”是 continuation marker，不得判多意图")
        // “温度再调高”非 catalog 批准短语 → L1（V2 分组2 一致），但绝不因多意图误判 L3。
        val decision = route("主驾温度再调高1度")
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, decision.tier)
        assertTrue(RouteReasonCode.L1_TOOL_DOMAIN == decision.reasonCode ||
            RouteReasonCode.L0_NO_MATCH == decision.reasonCode)
    }

    @Test
    fun `中左拓扑 adjust 路由到 L0`() {
        val decision = route("中左温度调高一点")
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, decision.tier)
        val candidate = decision.directCandidate
        assertEquals("climate.temperature.adjust", candidate!!.toolId)
        assertEquals("middle_left", candidate.arguments["zone"])
    }
}
