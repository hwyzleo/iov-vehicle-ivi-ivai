package net.hwyz.iov.vehicle.ivi.ivai.agenttest

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockGovernedToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
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
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway.AgentCommandGateway
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AgentTestCaseLoader
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AgentTestCaseRepository
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseStatus
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.TestBatchRunner
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentInputSource
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.AdapterRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.DefaultToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-012 端到端集成测试（IVAI-REQ-114/115/116/117/119/121）。
 *
 * 复用真实 AgentWorkflow + Mock 适配器（与 AgentService debug 相同的
 * GovernanceWorkspace + RuntimeCapabilityAssembler + 统一候选集），
 * TestBatchRunner 走与聊天相同的 HandleText 入口串行执行本地资产用例。
 *
 * 注意：本测试验证「套件解析 + 五维评分管线 + 批次状态机」等结构性契约，
 * 不逐条断言 30 条用例的命中准确率（准确率由 CR-012 v3 分层回归集后续
 * 单独评估）；仅对 Catalog 已批准 L0 规则（AIRFLOW-001/002/003）断言 5/5，
 * 对 NONE 目标语义做维度级断言。
 */
class AgentTestCr012IntegrationTest {

    /** 确定性模型桩：按队列返回 AgentOutput JSON（仅 L1/L2 等需要模型的路径会调用）。 */
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

    /** 进程内网关：真实 AgentWorkflow + 按 requestId 汇聚快照 + 隔离 Session。 */
    private class InProcessAgentClient(
        model: ModelProvider,
        private val submitDelayMs: (Int) -> Long = { 0 }
    ) : AgentCommandGateway {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val snapshots = ConcurrentHashMap<String, AgentEvaluationSnapshot>()
        private val sessions = mutableMapOf<String, Channel<AgentEvent>>()
        private val activeJobs = mutableMapOf<String, Job>()
        private var currentSession = Session()
        private var submitCount = 0
        private val workflow: AgentWorkflow = buildGraph(model)

        val snapshotsByRequest: Map<String, AgentEvaluationSnapshot> get() = snapshots

        override suspend fun createTestSession(): String {
            currentSession = Session()
            sessions[currentSession.sessionId] = Channel(Channel.UNLIMITED)
            return currentSession.sessionId
        }

        override suspend fun submitText(
            sessionId: String,
            requestId: String,
            text: String,
            source: AgentInputSource
        ): Boolean {
            val index = submitCount++
            val sessionAtSubmit = currentSession
            val delayMs = submitDelayMs(index)
            if (delayMs <= 0) {
                // 同步执行（测试确定性）：真实 AgentService 为异步启动，此处仅用于
                // 验证同一 workflow / 快照 / 评分链路。
                workflow.process(
                    AgentInput(requestId = requestId, text = text, source = source.name.lowercase(), turnId = requestId),
                    sessionAtSubmit
                )
                return true
            }
            // 异步执行（注入超时场景）。
            val job = scope.launch {
                delay(delayMs)
                workflow.process(
                    AgentInput(requestId = requestId, text = text, source = source.name.lowercase(), turnId = requestId),
                    sessionAtSubmit
                )
            }
            activeJobs[requestId] = job
            return true
        }

        override fun observe(sessionId: String): Flow<AgentEvent> =
            (sessions[sessionId] ?: Channel(Channel.UNLIMITED)).receiveAsFlow()

        override suspend fun evaluationSnapshot(requestId: String): AgentEvaluationSnapshot? = snapshots[requestId]

        override suspend fun cancelRequest(requestId: String): Boolean {
            activeJobs.remove(requestId)?.cancel()
            return true
        }

        // 进程内网关无全局单飞闸门（同步执行在 submit 内完成），等待恒为可用。
        override suspend fun awaitIdle(timeoutMs: Long): Boolean = true

        override fun cancelActiveRequest(): Boolean {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
            return true
        }

        private fun buildGraph(model: ModelProvider): AgentWorkflow {
            val governedAdapter = MockGovernedToolAdapter()
            val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
            val validator = ToolValidator(registry)
            val agentPolicy = AgentPolicyEngine(registry, ToolPolicyEngine())
            val executor = DefaultToolExecutor(
                registry = registry,
                adapterRegistry = AdapterRegistry().register(governedAdapter),
                lifecycleListener = null,
                executionTimeoutMs = 5_000
            )
            val promptBuilder = PromptBuilder(registry)
            val fastMatcher = DefaultFastIntentMatcher(registry)
            val domainClassifier = DomainClassifier(registry)
            val capabilitySelector = CapabilityPackSelector.governed(
                catalog = GovernanceWorkspace.runtimePacks(),
                governanceCatalog = GovernanceWorkspace.catalog,
                defaultEnvironment = GovernanceWorkspace.devStubEnvironment()
            )
            val tieredRouter = TieredIntentRouter(
                fastMatcher, domainClassifier,
                domainRouter = DomainRouter(registry),
                capabilitySelector = capabilitySelector,
                registry = registry
            )
            return AgentWorkflow(
                modelProvider = model,
                registry = registry,
                router = Router(),
                promptBuilder = promptBuilder,
                validator = validator,
                agentPolicy = agentPolicy,
                toolExecutor = executor,
                config = AgentConfig(model = "qwen3.5:4b", ollamaBaseUrl = "http://localhost:11434"),
                tieredRouter = tieredRouter,
                toolCandidateProvider = net.hwyz.iov.vehicle.ivi.ivai.agent.router.AllEnabledToolsProvider(registry),
                vehicleStateProvider = VehicleStateProvider { governedAdapter.snapshot() },
                eventListener = { event -> sessions[currentSession.sessionId]?.trySend(event) },
                evaluationListener = { snapshot -> snapshots[snapshot.requestId] = snapshot },
                softwareVersion = "0.1.0",
                vehicleModel = null
            )
        }
    }

    private fun loadSampleSuite(): List<net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase> {
        val file = File("src/debug/assets/agent-tests/v1/agent-regression.json")
        val repository = AgentTestCaseRepository.builtInOnly(AgentTestCaseLoader { file.takeIf { it.exists() }?.readText() })
        val result = repository.loadActiveSuite()
        assertNull(result.errorCode, "资产应可解析：${result.errorMessage}")
        return result.validCases
    }

    @Test
    fun `CR-012 v3 分层套件经真实 Workflow 串行回归 全部分与隔离 Session`() = runTest {
        // 需要模型的路径（L1 消歧 / 候选 L0 回退等）统一返回纯追问；队列留足余量。
        val model = QueuedModelProvider(*Array(60) { DIALOGUE_JSON })
        val client = InProcessAgentClient(model)
        val cases = loadSampleSuite()
        // CR-012 v3 分层回归集：3 分类 × 10 条，全部启用。
        assertEquals(30, cases.size)
        assertTrue(cases.all { it.enabled })

        val runner = TestBatchRunner(client, caseTimeoutMs = 5_000)
        val results = mutableListOf<net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseResult>()
        val summary = runner.run("v3-run", cases) { results += it }

        // 结构性契约：30 条全部执行、无跳过、满分 150。
        assertEquals(30, summary.executed)
        assertEquals(0, summary.skipped)
        assertEquals(30 * 5, summary.maxScore)
        // 每条都有五维评分结果；批次不出现超时 / 非法。
        assertEquals(30, results.size)
        assertTrue(results.all { it.score != null }, "每条用例都应产出五维评分")
        assertTrue(results.none { it.status == AgentTestCaseStatus.TIMEOUT })
        assertTrue(results.none { it.status == AgentTestCaseStatus.INVALID })

        // Catalog 已批准 L0 规则（climate.airflow.mode.set 三个正例）应直达 5/5。
        for (caseId in listOf("AIRFLOW-001", "AIRFLOW-002", "AIRFLOW-003")) {
            val r = results.first { it.caseId == caseId }
            assertEquals(AgentTestCaseStatus.PASSED, r.status, "$caseId 应为 Catalog L0 直达")
            assertEquals(5, r.score?.total, "$caseId 应五维全匹配")
        }

        // NONE 目标语义：REJECT 用例实际不产生目标时 target / arguments 维度匹配
        // （L2/L3/REJECT 无合法 Target，不伪造 Tool）。
        val reject = results.first { it.caseId == "REGEN-009" }
        assertTrue(reject.score?.target?.matched == true, "REJECT 应不产生目标：${reject.score?.target?.reason}")
        assertTrue(reject.score?.arguments?.matched == true, "REJECT 应无业务参数：${reject.score?.arguments?.reason}")
    }

    @Test
    fun `注入超时 → 取消活动请求并继续下一条`() = runTest {
        // 第一条延迟 500ms 模拟慢请求；Runner 单条超时 50ms。选用 Catalog 已批准
        // L0 用例（无需模型桩响应），第二条同步直达通过。
        val client = InProcessAgentClient(
            QueuedModelProvider(*Array(60) { DIALOGUE_JSON }),
            submitDelayMs = { if (it == 0) 500 else 0 }
        )
        val cases = loadSampleSuite().filter { it.caseId in setOf("AIRFLOW-001", "AIRFLOW-002") }

        val runner = TestBatchRunner(client, caseTimeoutMs = 50)
        val results = mutableListOf<net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseResult>()
        runner.run("timeout-run", cases) { results += it }

        assertEquals(2, results.size)
        assertEquals(AgentTestCaseStatus.TIMEOUT, results[0].status)
        assertEquals(TestErrorCode.CASE_TIMEOUT, results[0].errorCode)
        assertEquals(AgentTestCaseStatus.PASSED, results[1].status)
    }

    @Test
    fun `环境门禁 - Release 语义拒绝批次`() = runTest {
        val client = InProcessAgentClient(QueuedModelProvider(*Array(60) { DIALOGUE_JSON }))
        val cases = loadSampleSuite()
        val runner = TestBatchRunner(client, environmentAllowed = { false })
        val error = runCatching { runner.run("gate-run", cases) { } }.exceptionOrNull()
        assertTrue(error is net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.TestRunnerException)
        assertEquals(
            TestErrorCode.ENVIRONMENT_FORBIDDEN,
            (error as net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.TestRunnerException).errorCode
        )
    }

    companion object {
        /** L1 隐式表达：模型返回纯追问（LOCAL_DIALOGUE，无意图）。 */
        private val DIALOGUE_JSON = """
            {
              "route": "LOCAL_DIALOGUE",
              "intents": [],
              "modelConfidence": 0.8,
              "riskLevel": "low",
              "needConfirmation": false,
              "missingArguments": [],
              "reasonCode": null
            }
        """.trimIndent()
    }
}
