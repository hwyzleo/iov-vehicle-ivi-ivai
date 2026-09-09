package net.hwyz.iov.vehicle.ivi.ivai.ui.testcase

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.ActualTarget
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.TargetType
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.TestSuiteFixtures
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.export.ExportedFile
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway.AgentCommandGateway
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.AgentTestSuiteImporter
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
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.ExportState
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentInputSource
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * CR-015 测试页 ViewModel 单测：导入状态迁移、批次运行中拒绝切换、导入失败保留
 * 原 Suite 与已完成结果、批次启动冻结指纹、导出使用批次冻结快照。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentTestViewModelTest {

    @TempDir
    lateinit var tempDir: File

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 确定性测试网关：提交后立即发出终态事件并写入快照（每条用例 PASSED）。 */
    private inner class FakeGateway : AgentCommandGateway {
        private val channels = mutableMapOf<String, Channel<AgentEvent>>()
        private val snapshots = mutableMapOf<String, AgentEvaluationSnapshot>()
        private var sessionCounter = 0

        override suspend fun awaitIdle(timeoutMs: Long): Boolean = true
        override fun cancelActiveRequest(): Boolean = true

        override suspend fun createTestSession(): String {
            val id = "test-session-${sessionCounter++}"
            channels[id] = Channel(Channel.UNLIMITED)
            return id
        }

        override suspend fun submitText(
            sessionId: String,
            requestId: String,
            text: String,
            source: AgentInputSource
        ): Boolean {
            channels[sessionId]?.send(AgentEvent.Reply(sessionId, requestId, requestId, "ok"))
            snapshots[requestId] = defaultSnapshot(sessionId, requestId)
            return true
        }

        override fun observe(sessionId: String): Flow<AgentEvent> =
            (channels[sessionId] ?: Channel(Channel.UNLIMITED)).receiveAsFlow()

        override suspend fun evaluationSnapshot(requestId: String): AgentEvaluationSnapshot? =
            snapshots[requestId]

        override suspend fun cancelRequest(requestId: String): Boolean = true
    }

    private fun defaultSnapshot(sessionId: String, requestId: String) = AgentEvaluationSnapshot(
        requestId = requestId,
        sessionId = sessionId,
        initialTier = IntentTier.L0_DETERMINISTIC_TOOL,
        finalTier = IntentTier.L0_DETERMINISTIC_TOOL,
        finalDomain = BusinessDomainId.CABIN_COMFORT,
        selectedCapabilityPackIds = setOf("cabin.climate"),
        selectedTarget = ActualTarget(TargetType.TOOL, "climate.power.set"),
        normalizedArguments = buildJsonObject { put("enabled", true) },
        terminalStatus = EvaluationTerminalStatus.SUCCEEDED,
        reasonCode = "L0_UNIQUE_MATCH"
    )

    private fun buildViewModel(importData: ByteArray? = null): AgentTestViewModel {
        val store = ActiveSuiteStore(File(tempDir, "active"))
        val repo = AgentTestCaseRepository(
            builtIn = BuiltInSuiteSource(AgentTestCaseLoader { TestSuiteFixtures.builtinSuiteRaw }),
            active = ActiveFileSuiteSource(store),
            parser = AgentTestSuiteParser(),
            validator = AgentTestSuiteValidator()
        )
        val pipeline = SuiteImportPipeline(repository = repo)
        val importer = object : AgentTestSuiteImporter {
            override suspend fun import(uri: android.net.Uri): SuiteImportResult =
                importBytes(importData ?: ByteArray(0))

            override suspend fun importBytes(bytes: ByteArray): SuiteImportResult =
                if (importData == null) {
                    SuiteImportResult.Failure(TestErrorCode.IMPORT_URI_UNREADABLE, "未提供导入字节")
                } else {
                    pipeline.import(bytes)
                }
        }
        return AgentTestViewModel().also { vm ->
            vm.attach(gateway = FakeGateway(), repository = repo, importer = importer)
        }
    }

    private fun importVia(vm: AgentTestViewModel) {
        vm.importSuiteBytes(TestSuiteFixtures.validSuiteRaw.toByteArray())
        mainDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `load 加载内置 Suite 显示 READY 与来源内置`() {
        val vm = buildViewModel()
        vm.load()
        val s = vm.state.value
        assertEquals(TestRunState.READY, s.runState)
        assertEquals(SuiteSourceType.BUILT_IN, s.suiteSource)
        assertEquals("builtin-suite", s.suiteId)
        assertEquals(listOf("B-001"), s.cases.map { it.caseId })
    }

    @Test
    fun `导入成功刷新为激活 Suite 并清空旧批次状态`() {
        val vm = buildViewModel(importData = TestSuiteFixtures.validSuiteRaw.toByteArray())
        vm.load()
        // 先完成一个内置批次。
        vm.start()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(TestRunState.COMPLETED, vm.state.value.runState)
        assertEquals(ExportState.ENABLED, vm.state.value.exportState)

        // 导入新 Suite。
        importVia(vm)

        val s = vm.state.value
        assertEquals(SuiteImportState.SUCCEEDED, s.importState)
        assertEquals("suite-test-v1", s.suiteId)
        assertEquals(SuiteSourceType.ACTIVE_FILE, s.suiteSource)
        assertEquals(listOf("CASE-001", "CASE-002"), s.cases.map { it.caseId })
        assertEquals(TestRunState.READY, s.runState)
        // 旧批次导出状态与冻结快照被清空。
        assertEquals(ExportState.DISABLED, s.exportState)
        // 导入成功后导出提示「无已启动批次」。
        vm.exportXlsx()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ExportState.FAILED, vm.state.value.exportState)
        assertTrue(vm.state.value.exportErrorMessage!!.contains("无已启动的批次"))
    }

    @Test
    fun `导入失败保留当前 Suite 与已完成批次结果`() {
        val vm = buildViewModel(importData = "{ not json".toByteArray())
        vm.load()
        vm.start()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ExportState.ENABLED, vm.state.value.exportState)

        vm.importSuiteBytes("{ not json".toByteArray())
        mainDispatcher.scheduler.advanceUntilIdle()

        val s = vm.state.value
        assertEquals(SuiteImportState.FAILED, s.importState)
        assertTrue(s.importErrorMessage!!.contains("JSON 语法"), "应报告 JSON 语法错误：${s.importErrorMessage}")
        // 当前 Suite 与来源不变。
        assertEquals("builtin-suite", s.suiteId)
        assertEquals(SuiteSourceType.BUILT_IN, s.suiteSource)
        // 已完成批次结果与导出能力保留（导出使用批次冻结快照）。
        assertEquals(TestRunState.COMPLETED, s.runState)
        assertEquals(ExportState.ENABLED, s.exportState)
    }

    @Test
    fun `批次运行中导入被拒绝 IMPORT-007 且不切换 Suite`() {
        val vm = buildViewModel(importData = TestSuiteFixtures.validSuiteRaw.toByteArray())
        vm.load()
        vm.start()
        // 不推进调度器：批次处于 RUNNING。
        assertEquals(TestRunState.RUNNING, vm.state.value.runState)

        // 拒绝同步发生（importSuiteBytes 状态守卫）。
        vm.importSuiteBytes(TestSuiteFixtures.validSuiteRaw.toByteArray())
        val s = vm.state.value
        assertEquals(SuiteImportState.FAILED, s.importState)
        assertTrue(s.importErrorMessage!!.contains(TestErrorCode.IMPORT_BATCH_BLOCKED))
        // 来源仍为内置，未切换。
        assertEquals("builtin-suite", s.suiteId)
        assertEquals(SuiteSourceType.BUILT_IN, s.suiteSource)
        // 批次仍处于运行中（未被导入打断）。
        assertEquals(TestRunState.RUNNING, s.runState)

        // 批次继续执行并正常完成（导入被拒绝不影响批次）。
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(TestRunState.COMPLETED, vm.state.value.runState)
    }

    @Test
    fun `批次运行中 load 不重载当前列表`() {
        val vm = buildViewModel()
        vm.load()
        vm.start()
        assertEquals(TestRunState.RUNNING, vm.state.value.runState)
        vm.load()
        // 不打断运行中批次。
        assertEquals(TestRunState.RUNNING, vm.state.value.runState)
        assertEquals(listOf("B-001"), vm.state.value.cases.map { it.caseId })
        vm.cancel()
    }

    @Test
    fun `导出行来自批次冻结快照 不受后续失败导入影响`() {
        val vm = buildViewModel(importData = "{ not json".toByteArray())
        vm.load()
        vm.start()
        mainDispatcher.scheduler.advanceUntilIdle()

        var exported: ExportedFile? = null
        vm.onExportReady = { file -> exported = file }
        vm.exportXlsx()
        mainDispatcher.scheduler.advanceUntilIdle()

        assertTrue(exported != null, "批次完成后应可导出")
        // 导出行来自批次冻结的内置 Suite（B-001 及其输入）。
        val sheet = unzipSheet(exported!!.bytes)
        assertTrue(sheet.contains("B-001"))
        assertTrue(sheet.contains("打开空调"))
    }

    private fun unzipSheet(bytes: ByteArray): String {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
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
}
