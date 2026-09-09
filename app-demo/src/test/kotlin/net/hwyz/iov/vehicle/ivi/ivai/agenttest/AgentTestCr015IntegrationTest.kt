package net.hwyz.iov.vehicle.ivi.ivai.agenttest

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockGovernedToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptBuilder
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.AllEnabledToolsProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DefaultFastIntentMatcher
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DomainClassifier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.Router
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TieredIntentRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentInput
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentWorkflow
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.export.DefaultTestResultExporter
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway.AgentCommandGateway
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.AgentTestSuiteParser
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.AgentTestSuiteValidator
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.SuiteImportPipeline
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.SuiteImportResult
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.ActiveFileSuiteSource
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.ActiveSuiteStore
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AgentTestCaseLoader
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AgentTestCaseRepository
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.BuiltInSuiteSource
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.SuiteSourceType
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.TestCaseExecutionResult
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * CR-015 端到端集成测试：真实 AgentWorkflow + Mock 适配器，验证：
 *  - 现有资产 / 1,200 条生成 Suite 经「解析 → 校验 → 指纹 → 原子激活」后由
 *    Repository 优先加载（来源 ACTIVE_FILE）。
 *  - 导入后的用例仍走既有 TestBatchRunner → AgentCommandGateway → AgentWorkflow
 *    链路（不因文件来源改变路由 / 门禁），串行执行并导出 XLSX。
 *  - 非法文件整包拒绝；激活文件损坏回退内置并提示原因；重建后优先加载激活 Suite。
 */
class AgentTestCr015IntegrationTest {

    @TempDir
    lateinit var tempDir: File

    // ---- 仓库 / 管线构建（激活目录可注入，模拟 filesDir/agent-tests/active） ----

    private fun repository(storeDir: File = File(tempDir, "active")): AgentTestCaseRepository =
        AgentTestCaseRepository(
            builtIn = BuiltInSuiteSource(
                AgentTestCaseLoader {
                    File("src/debug/assets/agent-tests/v1/agent-regression.json")
                        .takeIf { it.exists() }
                        ?.readText()
                }
            ),
            active = ActiveFileSuiteSource(ActiveSuiteStore(storeDir)),
            parser = AgentTestSuiteParser(),
            validator = AgentTestSuiteValidator()
        )

    private fun pipeline(repo: AgentTestCaseRepository) = SuiteImportPipeline(repository = repo)

    // ---- 1,200 条 L0 用例生成（AIRFLOW-001 已知通过形状） ----

    private fun generateL0Cases(count: Int): String = buildString {
        append("""{"suiteId": "imported-1200-suite", "schemaVersion": 1, "governanceVersion": "ivai-governance-v1-draft", "cases": [""")
        for (i in 1..count) {
            if (i > 1) append(",")
            append(
                """
                {
                  "caseId": "AIRFLOW-%04d",
                  "description": "批量导入用例 $i",
                  "tags": ["L0", "imported"],
                  "enabled": true,
                  "input": "出风模式吹脸",
                  "expectedTier": "L0_DETERMINISTIC_TOOL",
                  "expectedDomain": "CABIN_COMFORT",
                  "expectedCapabilityPack": "cabin.climate",
                  "expectedTarget": { "type": "TOOL", "id": "climate.airflow.mode.set" },
                  "expectedArguments": { "mode": "face" }
                }
                """.trimIndent().format(i)
            )
        }
        append("]}")
    }

    // ---- 进程内网关：真实 AgentWorkflow + 事件流 + 快照 + 隔离 Session（CR-014 同款） ----

    private class InProcessAgentClient : AgentCommandGateway {

        private val snapshots = ConcurrentHashMap<String, AgentEvaluationSnapshot>()
        private val sessions = mutableMapOf<String, Channel<AgentEvent>>()
        private var currentSession = Session()
        private val workflow: AgentWorkflow = buildGraph()

        private fun buildGraph(): AgentWorkflow {
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
                modelProvider = NoopModelProvider(),
                registry = registry,
                router = Router(),
                promptBuilder = promptBuilder,
                validator = validator,
                agentPolicy = agentPolicy,
                toolExecutor = executor,
                config = AgentConfig(model = "qwen3.5:4b", ollamaBaseUrl = "http://localhost:11434"),
                tieredRouter = tieredRouter,
                toolCandidateProvider = AllEnabledToolsProvider(registry),
                vehicleStateProvider = VehicleStateProvider { governedAdapter.snapshot() },
                eventListener = { event ->
                    sessions[currentSession.sessionId]?.trySend(event)
                },
                evaluationListener = { snapshot -> snapshots[snapshot.requestId] = snapshot },
                softwareVersion = "0.1.0",
                vehicleModel = null
            )
        }

        override suspend fun awaitIdle(timeoutMs: Long): Boolean = true
        override fun cancelActiveRequest(): Boolean = true

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

        override suspend fun evaluationSnapshot(requestId: String): AgentEvaluationSnapshot? =
            snapshots[requestId]

        override suspend fun cancelRequest(requestId: String): Boolean = true
    }

    /** L0 用例不调用模型；若被调用则直接失败（证明导入不改变路由）。 */
    private class NoopModelProvider : StreamingModelProvider {
        override suspend fun generate(request: ModelRequest): ModelResponse =
            error("L0 用例不应调用模型")
        override suspend fun generateStreaming(
            request: ModelRequest,
            onDelta: suspend (String) -> Unit
        ): ModelResponse = error("L0 用例不应调用模型")
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

    // ---- 测试 ----

    @Test
    fun `真实资产导入成功 激活来源切换且数据一致`() {
        val bytes = File("src/debug/assets/agent-tests/v1/agent-regression.json").readBytes()
        val repo = repository()
        val result = pipeline(repo).import(bytes)
        assertTrue(result is SuiteImportResult.Success, "真实资产应导入成功：$result")
        val loaded = (result as SuiteImportResult.Success).loaded
        assertEquals("ivai-agent-layered-regression-v3", loaded.suite?.suiteId)
        assertEquals(30, loaded.caseCount)
        assertEquals(SuiteSourceType.ACTIVE_FILE, loaded.source)
        assertTrue(loaded.validCases.isNotEmpty())
    }

    @Test
    fun `导入 1200 条 Suite 串行执行并导出 Excel 1200 行`() = runTest(timeout = 120.seconds) {
        val bytes = generateL0Cases(1200).toByteArray()
        val repo = repository()
        assertTrue(pipeline(repo).import(bytes) is SuiteImportResult.Success)

        val loaded = repo.loadActiveSuite()
        assertEquals(1200, loaded.caseCount)
        assertEquals("imported-1200-suite", loaded.suite?.suiteId)
        assertEquals(SuiteSourceType.ACTIVE_FILE, loaded.source)

        // 导入后的用例继续走既有串行执行链路（真实 AgentWorkflow，L0 不调用模型）。
        val runner = TestBatchRunner(InProcessAgentClient(), caseTimeoutMs = 5_000)
        val executions = mutableListOf<TestCaseExecutionResult>()
        val results = mutableListOf<net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseResult>()
        runner.run(
            "cr15-1200",
            loaded.validCases,
            onExecutionResult = { executions += it },
            onCaseResult = { results += it }
        )

        assertEquals(1200, results.size)
        assertEquals(1200, executions.size)
        assertTrue(results.all { it.status == AgentTestCaseStatus.PASSED }, "1,200 条 L0 应全部通过")
        assertTrue(executions.all { it.status.isTerminal })

        // 导出：表头 + 1200 行，顺序与声明一致（批次冻结快照）。
        val file = DefaultTestResultExporter().exportXlsx("cr15-1200", executions)
        val sheet = unzipSheet(file.bytes)
        assertEquals(1201, Regex("""<row r=""").findAll(sheet).count(), "应导出 1200 条用例 + 表头")
        assertTrue(sheet.contains("AIRFLOW-0001"))
        assertTrue(sheet.contains("AIRFLOW-1200"))
        // 首行冻结 + 自动筛选覆盖全部行。
        assertTrue(sheet.contains("""<autoFilter ref="A1:AG1201"/>""")) // CR-018 33 列
    }

    @Test
    fun `非法文件整包拒绝 内置保持可加载`() {
        val repo = repository()
        val dup = generateL0Cases(3).replace("\"AIRFLOW-0002\"", "\"AIRFLOW-0001\"")
        val result = pipeline(repo).import(dup.toByteArray())
        assertTrue(result is SuiteImportResult.Failure)
        assertEquals(
            TestErrorCode.IMPORT_VALIDATION_FAILED,
            (result as SuiteImportResult.Failure).errorCode
        )
        // 未写入激活文件：仍加载内置资产。
        val loaded = repo.loadActiveSuite()
        assertEquals("ivai-agent-layered-regression-v3", loaded.suite?.suiteId)
        assertEquals(SuiteSourceType.BUILT_IN, loaded.source)
    }

    @Test
    fun `重新进入页面优先加载激活 Suite（重建语义）`() {
        // 第一次会话导入并激活。
        val bytes = generateL0Cases(10).toByteArray()
        val repo1 = repository()
        assertTrue(pipeline(repo1).import(bytes) is SuiteImportResult.Success)

        // 模拟 Activity 重建：同一激活目录创建新的仓库实例（新的 Store / Source）。
        val repo2 = repository()
        val loaded = repo2.loadActiveSuite()
        assertEquals("imported-1200-suite", loaded.suite?.suiteId)
        assertEquals(10, loaded.caseCount)
        assertEquals(SuiteSourceType.ACTIVE_FILE, loaded.source)
        assertNull(loaded.errorCode)
    }

    @Test
    fun `破坏激活文件或描述符 → 回退内置并提示原因`() {
        val storeDir = File(tempDir, "active")
        val bytes = generateL0Cases(5).toByteArray()
        val repo1 = repository(storeDir)
        assertTrue(pipeline(repo1).import(bytes) is SuiteImportResult.Success)

        // 破坏描述符（模拟损坏 / 部分写入）。
        File(storeDir, "active-suite.json").writeText("{ broken descriptor")

        val repo2 = repository(storeDir)
        val loaded = repo2.loadActiveSuite()
        assertEquals("ivai-agent-layered-regression-v3", loaded.suite?.suiteId)
        assertEquals(SuiteSourceType.BUILT_IN, loaded.source)
        assertTrue(loaded.degradedReason != null && loaded.degradedReason.contains("已回退"))
        assertEquals(30, loaded.caseCount)
    }

    @Test
    fun `使用导入 Suite 执行后 导出行来自批次冻结快照`() = runTest(timeout = 60.seconds) {
        val bytes = generateL0Cases(5).toByteArray()
        val repo = repository()
        assertTrue(pipeline(repo).import(bytes) is SuiteImportResult.Success)
        val loaded = repo.loadActiveSuite()

        val runner = TestBatchRunner(InProcessAgentClient(), caseTimeoutMs = 5_000)
        val executions = mutableListOf<TestCaseExecutionResult>()
        runner.run(
            "cr15-frozen",
            loaded.validCases,
            onExecutionResult = { executions += it },
            onCaseResult = {}
        )

        // 导出行来自批次冻结的导入 Suite（AIRFLOW-0001..0005 与同一输入）。
        val file = DefaultTestResultExporter().exportXlsx("cr15-frozen", executions)
        val sheet = unzipSheet(file.bytes)
        (1..5).forEach { i ->
            assertTrue(sheet.contains("AIRFLOW-%04d".format(i)), "导出行应包含 AIRFLOW-${i}")
        }
        // 期望值来自导入 Suite（climate.airflow.mode.set / mode=face）。
        assertTrue(sheet.contains("climate.airflow.mode.set"))
        assertTrue(sheet.contains("""
            {"mode":"face"}
        """.trimIndent()))
    }
}
