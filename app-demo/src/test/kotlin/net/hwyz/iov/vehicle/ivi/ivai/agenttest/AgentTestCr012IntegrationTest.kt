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
 */
class AgentTestCr012IntegrationTest {

    /** 确定性模型桩：按队列返回 AgentOutput JSON（仅 L1/L2 会调用模型）。 */
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
        val repository = AgentTestCaseRepository(AgentTestCaseLoader { file.takeIf { it.exists() }?.readText() })
        val result = repository.load()
        assertNull(result.errorCode, "示例资产应可解析：${result.errorMessage}")
        return result.validCases
    }

    @Test
    fun `本地资产套件经真实 Workflow 串行回归 全部分与隔离 Session`() = runTest {
        val model = QueuedModelProvider(DIALOGUE_JSON) // 仅「我有点冷」走 L1 调用一次模型
        val client = InProcessAgentClient(model)
        val cases = loadSampleSuite()
        // 5 条合法（4 enabled + 1 disabled），disabled 在运行时跳过。
        assertEquals(5, cases.size)

        val runner = TestBatchRunner(client, caseTimeoutMs = 5_000)
        val results = mutableListOf<net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseResult>()
        val summary = runner.run("integration-run", cases) { results += it }

        // 4 条执行 + 1 条跳过（跳过不提交）。
        assertEquals(4, summary.executed)
        assertEquals(1, summary.skipped)
        assertEquals(4, summary.passed)
        assertEquals(20, summary.score)
        assertEquals(20, summary.maxScore)

        // 打开空调 → 5/5。
        val power = results.first { it.caseId == "CLIMATE-POWER-001" }
        assertEquals(AgentTestCaseStatus.PASSED, power.status)
        assertEquals(5, power.score?.total)

        // 主驾升温（表达 Alias）→ 5/5。
        val adjust = results.first { it.caseId == "CLIMATE-ADJUST-001" }
        assertEquals(5, adjust.score?.total)

        // 槽位抽取（两度 → step=2，数值等价）→ 5/5。
        val slots = results.first { it.caseId == "CLIMATE-ADJUST-002" }
        assertEquals(5, slots.score?.total)

        // 隐式表达：记录 L1 终态且不阻塞批次。
        val cold = results.first { it.caseId == "CLIMATE-COLD-001" }
        assertEquals(5, cold.score?.total)
        val coldSnap = client.snapshotsByRequest.entries.first { it.value.finalTier == IntentTier.L1_LOCAL_TOOL_REASONING }.value
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, coldSnap.finalTier)
        assertEquals(BusinessDomainId.CABIN_COMFORT, coldSnap.finalDomain)
        assertTrue("cabin.climate" in coldSnap.selectedCapabilityPackIds)
        assertNull(coldSnap.selectedTarget)
    }

    @Test
    fun `注入超时 → 取消活动请求并继续下一条`() = runTest {
        // 第一条延迟 500ms 模拟慢请求；Runner 单条超时 50ms。
        val client = InProcessAgentClient(QueuedModelProvider(DIALOGUE_JSON), submitDelayMs = { if (it == 0) 500 else 0 })
        val cases = loadSampleSuite().filter { it.caseId in setOf("CLIMATE-POWER-001", "CLIMATE-ADJUST-001") }

        val runner = TestBatchRunner(client, caseTimeoutMs = 50)
        val results = mutableListOf<net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseResult>()
        runner.run("timeout-run", cases) { results += it }

        assertEquals(AgentTestCaseStatus.TIMEOUT, results[0].status)
        assertEquals(TestErrorCode.CASE_TIMEOUT, results[0].errorCode)
        assertEquals(AgentTestCaseStatus.PASSED, results[1].status)
    }

    @Test
    fun `环境门禁 - Release 语义拒绝批次`() = runTest {
        val client = InProcessAgentClient(QueuedModelProvider(DIALOGUE_JSON))
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
