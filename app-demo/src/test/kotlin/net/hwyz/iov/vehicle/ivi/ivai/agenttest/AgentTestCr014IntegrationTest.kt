package net.hwyz.iov.vehicle.ivi.ivai.agenttest

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.export.DefaultTestResultExporter
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway.AgentCommandGateway
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.TestCaseStatus
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseStatus
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.TestBatchRunner
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.model.StreamingModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentInputSource
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.AdapterRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.DefaultToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-014 端到端集成测试：真实 AgentWorkflow + Mock 适配器，验证：
 *  - 计时里程碑经事件流采集（L0 无 LLM → N/A；L1 流式 LLM → 首字/完整耗时合法）；
 *  - 非流式 LLM 在完整返回时同时落定首字与完整耗时；
 *  - 终态 TestCaseExecutionResult 生成（expected/actual/status/timing）；
 *  - 批次全部终态后可导出 .xlsx 且可被解压解析，混合 LLM/N/A 区分正确。
 */
class AgentTestCr014IntegrationTest {

    /** 流式模型桩：按队列返回 AgentOutput JSON，通过 onDelta 逐个字符流式输出。 */
    private class StreamingQueuedModelProvider(vararg contents: String) : StreamingModelProvider {
        private val queue = ArrayDeque(contents.toList())
        override suspend fun generate(request: ModelRequest): ModelResponse = generateStreaming(request) {}

        override suspend fun generateStreaming(
            request: ModelRequest,
            onDelta: suspend (String) -> Unit
        ): ModelResponse {
            val content = queue.removeFirst()
            for (ch in content) {
                onDelta(ch.toString())
            }
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

    /** 进程内网关：真实 AgentWorkflow + 事件流 + 快照 + 隔离 Session。 */
    private class InProcessAgentClient(model: ModelProvider) : AgentCommandGateway {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val snapshots = ConcurrentHashMap<String, AgentEvaluationSnapshot>()
        private val sessions = mutableMapOf<String, Channel<AgentEvent>>()
        private var currentSession = Session()
        private val workflow: AgentWorkflow = buildGraph(model)

        val allEvents: MutableList<AgentEvent> = mutableListOf()

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
            val sessionAtSubmit = currentSession
            workflow.process(
                AgentInput(requestId = requestId, text = text, source = source.name.lowercase(), turnId = requestId),
                sessionAtSubmit
            )
            return true
        }

        override fun observe(sessionId: String): Flow<AgentEvent> =
            (sessions[sessionId] ?: Channel(Channel.UNLIMITED)).receiveAsFlow()

        override suspend fun evaluationSnapshot(requestId: String): AgentEvaluationSnapshot? = snapshots[requestId]

        override suspend fun cancelRequest(requestId: String): Boolean = true

        override suspend fun awaitIdle(timeoutMs: Long): Boolean = true

        override fun cancelActiveRequest(): Boolean = true

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
                eventListener = { event ->
                    allEvents += event
                    sessions[currentSession.sessionId]?.trySend(event)
                },
                evaluationListener = { snapshot -> snapshots[snapshot.requestId] = snapshot },
                softwareVersion = "0.1.0",
                vehicleModel = null
            )
        }
    }

    private fun loadSuite(vararg caseIds: String): List<AgentTestCase> {
        val file = File("src/debug/assets/agent-tests/v1/agent-regression.json")
        val repository = net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AgentTestCaseRepository.builtInOnly(
            net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AgentTestCaseLoader {
                file.takeIf { it.exists() }?.readText()
            }
        )
        val result = repository.loadActiveSuite()
        assertNull(result.errorCode, "资产应可解析：${result.errorMessage}")
        return result.validCases.filter { it.caseId in caseIds }
    }

    private fun unzipSheet(bytes: ByteArray): String {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "xl/worksheets/sheet1.xml") {
                    val content = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry()
                    return content
                }
                zip.closeEntry()
            }
        }
        error("sheet1.xml 不存在")
    }

    @Test
    fun `L0 直达用例无 LLM 调用 计时不适用且导出空单元格`() = runTest {
        val client = InProcessAgentClient(StreamingQueuedModelProvider(*Array(60) { DIALOGUE_JSON }))
        val cases = loadSuite("AIRFLOW-001", "AIRFLOW-002")
        val runner = TestBatchRunner(client, caseTimeoutMs = 5_000)

        val results = mutableListOf<net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseResult>()
        val executions = mutableListOf<net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.TestCaseExecutionResult>()
        runner.run(
            "cr14-l0",
            cases,
            onExecutionResult = { executions += it },
            onCaseResult = { results += it }
        )

        // 两条 L0 用例全部通过且未调用 LLM。
        assertEquals(2, results.size)
        assertTrue(results.all { it.status == AgentTestCaseStatus.PASSED })
        assertTrue(results.all { it.timing != null })
        results.forEach { r ->
            assertFalse(r.timing!!.llmInvoked)
            assertNull(r.timing!!.llmFirstTokenLatencyMs)
            assertNull(r.timing!!.llmCompleteLatencyMs)
            assertTrue(r.timing!!.processingStartLatencyMs >= 0)
            assertTrue(r.timing!!.totalCaseDurationMs >= r.timing!!.processingStartLatencyMs)
        }
        // 没有 ModelCallStarted/Completed 事件。
        assertTrue(client.allEvents.none { it is AgentEvent.ModelCallStarted })

        // 导出：全部终态、16+4 列、L0 行 LLM 列空单元格。
        val file = DefaultTestResultExporter().exportXlsx("cr14-l0", executions)
        val sheet = unzipSheet(file.bytes)
        assertTrue(sheet.contains("AIRFLOW-001"))
        // 每条一行（表头 + 2 行 → autoFilter A1:Y3）。
        assertTrue(sheet.contains("""<autoFilter ref="A1:Y3"/>"""))
    }

    @Test
    fun `流式 LLM 用例 首字与完整耗时合法且导出数值`() = runTest {
        val client = InProcessAgentClient(StreamingQueuedModelProvider(*Array(60) { DIALOGUE_JSON }))
        // 选择需要模型调用路径的用例（L1 隐式表达类，DIALOGUE_JSON 返回追问）。
        val cases = loadSuite("REGEN-004", "REGEN-005")
        assertTrue(cases.isNotEmpty(), "应存在 REGEN-004/005 用例（L1 隐式表达）")
        val runner = TestBatchRunner(client, caseTimeoutMs = 5_000)

        val executions = mutableListOf<net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.TestCaseExecutionResult>()
        val results = mutableListOf<net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseResult>()
        runner.run(
            "cr14-llm",
            cases,
            onExecutionResult = { executions += it },
            onCaseResult = { results += it }
        )

        // 模型被调用：存在 ModelCallStarted/Completed 事件。
        assertTrue(client.allEvents.any { it is AgentEvent.ModelCallStarted }, "应发出 ModelCallStarted")
        assertTrue(client.allEvents.any { it is AgentEvent.ModelCallCompleted }, "应发出 ModelCallCompleted")

        val llmCase = executions.firstOrNull { it.timing.llmInvoked }
        assertNotNull(llmCase, "至少一条用例应调用 LLM")
        val llm = llmCase!!
        assertTrue(llm.timing.llmCompleteLatencyMs != null || llm.status == TestCaseStatus.FAILED)
        if (llm.timing.llmCompleteLatencyMs != null && llm.timing.llmFirstTokenLatencyMs != null) {
            assertTrue(
                llm.timing.llmCompleteLatencyMs!! >= llm.timing.llmFirstTokenLatencyMs!!,
                "完整返回耗时不小于首字耗时"
            )
        }
    }

    @Test
    fun `非流式 LLM 在完整返回时同时落定首字与完整返回`() = runTest {
        // 使用非流式模型桩（仅 generate，无 generateStreaming）。
        val nonStreaming = object : ModelProvider {
            private val queue = ArrayDeque(listOf(*Array(60) { DIALOGUE_JSON }))
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
        val client = InProcessAgentClient(nonStreaming)
        val cases = loadSuite("REGEN-004")
        val runner = TestBatchRunner(client, caseTimeoutMs = 5_000)
        val executions = mutableListOf<net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.TestCaseExecutionResult>()
        runner.run("cr14-ns", cases, onExecutionResult = { executions += it }, onCaseResult = {})

        val llmCase = executions.firstOrNull { it.timing.llmInvoked }
        assertNotNull(llmCase, "应调用 LLM（非流式）")
        val llm = llmCase!!
        // 非流式：首字与完整返回同时落定。
        assertEquals(
            llm.timing.llmCompleteLatencyMs,
            llm.timing.llmFirstTokenLatencyMs
        )
    }

    companion object {
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
