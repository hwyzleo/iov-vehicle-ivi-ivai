package net.hwyz.iov.vehicle.ivi.ivai.agenttest

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockGovernedToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptBuilder
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DefaultFastIntentMatcher
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DomainClassifier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.Router
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TieredIntentRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentInput
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentWorkflow
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.TestResultDiagnostics
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.AgentTestSuiteParser
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestSuite
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring.ScoredActual
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring.TestScorer
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.AdapterRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.DefaultToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-018 端到端集成测试：ivai-agent-climate-power-boundary-regression-v2（200 条）。
 *
 * 复用真实 AgentWorkflow + 治理桩图（与 AgentService debug 相同的 GovernanceWorkspace +
 * RuntimeCapabilityAssembler + 统一候选集），逐条以隔离 Session 串行执行，快照评分。
 *
 * 验收门槛：
 *  - 200 条分布：59 L0 / 141 L1；power 80 / vent 50 / fan 40 / airflow 20 / auto 10；
 *  - Domain 与 Capability Pack 一致率 100%；
 *  - 不得出现存在合法本地候选却无原因直接进入 L3（IVAI-ROUTE-LOCAL-001 不出现）；
 *  - 59 条 L0 的 Tier / Target / Arguments 通过率 100%；
 *  - 141 条 L1 必须调用模型（llmInvoked=true）；
 *  - 五类 Tool 最终 Target 准确率 100%（模型桩正确选择 + 候选边界）；
 *  - L1 诊断导出字段（Domain 证据 / RAG Top-K）非空。
 */
class AgentTestCr018IntegrationTest {

    private class QueuedModelProvider(vararg contents: String) : ModelProvider {
        private val queue = ArrayDeque(contents.toList())
        override suspend fun generate(request: ModelRequest): ModelResponse {
            val content = queue.removeFirst()
            return ModelResponse(
                requestId = request.requestId,
                content = content,
                contentJson = Json.parseToJsonElement(content),
                model = "stub",
                finishReason = "stop",
                latencyMs = 1
            )
        }
    }

    private fun loadSuite(): AgentTestSuite {
        val file = File("src/debug/assets/agent-tests/v2/agent-regression.json")
        assertTrue(file.exists(), "V2 套件资产必须存在: ${file.absolutePath}")
        return AgentTestSuiteParser().parse(file.readText())
    }

    /** L1 用例的模型响应（模型桩按套件顺序正确选择目标 Tool）。 */
    private fun modelResponse(case: net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase): String =
        """{"route":"LOCAL_TOOL","intents":[{"toolId":"${case.expectedTarget!!.id}","arguments":${case.expectedArguments}}],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"CR018-L1"}"""

    private fun buildWorkflow(
        model: ModelProvider,
        holder: SnapshotHolderLocal
    ): AgentWorkflow {
        val governedAdapter = MockGovernedToolAdapter()
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        val validator = ToolValidator(registry)
        val agentPolicy = AgentPolicyEngine(registry, ToolPolicyEngine())
        val executor = DefaultToolExecutor(
            registry = registry,
            adapterRegistry = AdapterRegistry().register(governedAdapter),
            executionTimeoutMs = 5_000
        )
        val capabilitySelector = CapabilityPackSelector.governed(
            catalog = GovernanceWorkspace.runtimePacks(),
            governanceCatalog = GovernanceWorkspace.catalog,
            defaultEnvironment = GovernanceWorkspace.devStubEnvironment()
        )
        val tieredRouter = TieredIntentRouter(
            DefaultFastIntentMatcher(registry), DomainClassifier(registry),
            domainRouter = DomainRouter(registry),
            capabilitySelector = capabilitySelector,
            registry = registry
        )
        return AgentWorkflow(
            modelProvider = model,
            registry = registry,
            router = Router(),
            promptBuilder = PromptBuilder(registry),
            validator = validator,
            agentPolicy = agentPolicy,
            toolExecutor = executor,
            config = AgentConfig(model = "qwen3.5:4b", ollamaBaseUrl = "http://localhost:11434"),
            tieredRouter = tieredRouter,
            toolCandidateProvider = net.hwyz.iov.vehicle.ivi.ivai.agent.router.AllEnabledToolsProvider(registry),
            vehicleStateProvider = VehicleStateProvider { governedAdapter.snapshot() },
            evaluationListener = { holder.snapshot = it },
            softwareVersion = "0.1.0",
            vehicleModel = null
        )
    }

    private class SnapshotHolderLocal {
        var snapshot: AgentEvaluationSnapshot? = null
    }

    @Test
    fun `V2 套件 200 条通过 Agent 链路且满足 CR-018 验收门槛`() = runTest {
        val suite = loadSuite()
        // ---- 套件分布 ----
        assertEquals("ivai-agent-climate-power-boundary-regression-v2", suite.suiteId)
        val cases = suite.cases
        assertEquals(200, cases.size)
        val l0 = cases.filter { it.expectedTier == IntentTier.L0_DETERMINISTIC_TOOL }
        val l1 = cases.filter { it.expectedTier == IntentTier.L1_LOCAL_TOOL_REASONING }
        assertEquals(59, l0.size, "L0 应为 59（当前 Catalog 批准短语）")
        assertEquals(141, l1.size, "L1 应为 141")
        val byTool = cases.groupingBy { it.expectedTarget?.id }.eachCount()
        assertEquals(80, byTool["climate.power.set"])
        assertEquals(50, byTool["climate.vent.set"])
        assertEquals(40, (byTool["climate.fan.speed.set"] ?: 0) + (byTool["climate.fan.speed.adjust"] ?: 0))
        assertEquals(20, byTool["climate.airflow.mode.set"])
        assertEquals(10, byTool["climate.auto.set"])

        // ---- 运行 ----
        val responses = l1.map { modelResponse(it) }
        val holder = SnapshotHolderLocal()
        val workflow = buildWorkflow(QueuedModelProvider(*responses.toTypedArray()), holder)

        var passed = 0
        var l0Passed = 0
        var l3WithLocalCandidates = 0
        val failures = mutableListOf<String>()
        val domainMismatches = mutableListOf<String>()
        val packMismatches = mutableListOf<String>()
        val l1WithoutModel = mutableListOf<String>()

        for (case in cases) {
            val session = Session()
            workflow.process(AgentInput(requestId = case.caseId, text = case.input), session)
            val snapshot = holder.snapshot
            // 快照必须存在（先写快照再发终态事件）。
            assertTrue(snapshot != null, "${case.caseId} 快照缺失")
            val actual = ScoredActual(
                finalTier = snapshot!!.finalTier,
                finalDomain = snapshot.finalDomain,
                actualCapabilityPacks = snapshot.selectedCapabilityPackIds,
                target = snapshot.selectedTarget,
                arguments = snapshot.normalizedArguments
            )
            val score = TestScorer.score(case, actual)
            if (score.total == 5) passed++ else failures += (
                "${case.caseId}:${case.input} → ${score.total}/5" +
                    "(tier=${snapshot.finalTier}, target=${snapshot.selectedTarget}, " +
                    "args=${snapshot.normalizedArguments}, reason=${snapshot.reasonCode})"
                )
            if (case.expectedTier == IntentTier.L0_DETERMINISTIC_TOOL && score.total == 5) l0Passed++
            if (snapshot.finalDomain != BusinessDomainId.CABIN_COMFORT) domainMismatches += case.caseId
            if ("cabin.climate" !in snapshot.selectedCapabilityPackIds) packMismatches += case.caseId
            if (case.expectedTier == IntentTier.L1_LOCAL_TOOL_REASONING && snapshot.llmInvoked != true) {
                l1WithoutModel += "${case.caseId}:${case.input}(tier=${snapshot.finalTier},reason=${snapshot.reasonCode},stage=${snapshot.terminalStage})"
            }
            // 合法本地候选却直入 L3 视为违规（IVAI-ROUTE-LOCAL-001）。
            if (snapshot.finalTier == IntentTier.L3_CLOUD_AI &&
                snapshot.selectedCapabilityPackIds.isNotEmpty()
            ) {
                l3WithLocalCandidates++
            }
        }

        // ---- 验收 ----
        assertTrue(domainMismatches.isEmpty(), "Domain 一致率 100% 失败: $domainMismatches")
        assertTrue(packMismatches.isEmpty(), "Pack 一致率 100% 失败: $packMismatches")
        assertEquals(0, l3WithLocalCandidates, "不得出现合法本地候选却无原因 L3")
        assertEquals(59, l0Passed, "59 条 L0 Tier/Target/Arguments 应 100% 通过，失败: ${failures.take(20)}")
        assertTrue(l1WithoutModel.isEmpty(), "141 条 L1 必须调用模型: $l1WithoutModel")
        assertTrue(failures.isEmpty(), "五类 Tool 最终 Target 准确率应 100%，失败: ${failures.take(20)}")
        assertEquals(200, passed, "全量 200 条应通过五维评分")
    }

    @Test
    fun `L1 用例诊断导出字段非空`() = runTest {
        val suite = loadSuite()
        val l1 = suite.cases.filter { it.expectedTier == IntentTier.L1_LOCAL_TOOL_REASONING }.take(3)
        val holder = SnapshotHolderLocal()
        val workflow = buildWorkflow(
            QueuedModelProvider(*l1.map { modelResponse(it) }.toTypedArray()),
            holder
        )
        for (case in l1) {
            val session = Session()
            workflow.process(AgentInput(requestId = case.caseId, text = case.input), session)
            val snapshot = holder.snapshot
            assertTrue(snapshot != null)
            val diag = TestResultDiagnostics.project(snapshot)
            assertTrue(diag.retrievedCandidateIds.isNotEmpty(), "${case.caseId} RAG Top-K 应非空")
            assertTrue(diag.finalDomain != null, "${case.caseId} Domain 证据应非空")
        }
    }
}
