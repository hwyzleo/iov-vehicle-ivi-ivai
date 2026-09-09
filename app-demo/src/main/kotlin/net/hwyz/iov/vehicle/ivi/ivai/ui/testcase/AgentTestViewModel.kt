package net.hwyz.iov.vehicle.ivi.ivai.ui.testcase

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.export.DefaultTestResultExporter
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.export.ExportValidationException
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.export.ExportedFile
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.export.TestResultExporter
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway.AgentCommandGateway
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.AgentTestSuiteImporter
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.SuiteImportResult
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AgentTestCaseRepository
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.CaseVerdict
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.LoadedAgentTestSuite
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.ExportState
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.IntentExpectation
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.JsonValueMapper
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.TestCaseExecutionResult
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.TestCaseStatus
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseResult
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseStatus
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.TestBatchRunner
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.TestRunnerException
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.TestRunSummary
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.timing.TestCaseTiming

/**
 * 测试用例页 ViewModel（IVI-IVAI-DSN-CR-012 / CR-014 / CR-015）。
 *
 *  - 持有批次 Job 与内存结果，配置变化不重启批次。
 *  - 页面退出但 Activity 未被系统销毁时可继续或取消；首期显式取消并保留已完成结果。
 *  - 进程退出后不恢复未完成批次，重新进入显示未运行。
 *  - 开始按钮在 RUNNING / CANCELLING 状态禁用，防止重复批次（IVAI-TEST-008）。
 *  - CR-014：批次全部终态后才允许导出；导出期间防止重复触发；失败保留结果并提示。
 *  - CR-015：Suite JSON 导入与原子激活；批次启动时冻结 suiteId + schemaVersion +
 *    sha256 与用例快照，执行与 Excel 导出始终使用同一批次快照（导入不重写旧结果）。
 */
class AgentTestViewModel : ViewModel() {

    private val _state = MutableStateFlow(AgentTestUiState())
    val state: StateFlow<AgentTestUiState> = _state.asStateFlow()

    private var gateway: AgentCommandGateway? = null
    private var repository: AgentTestCaseRepository? = null
    private var importer: AgentTestSuiteImporter? = null
    private var runner: TestBatchRunner? = null
    private var batchJob: Job? = null

    /** 当前 Suite 的合法用例（声明顺序）。 */
    private var loadedCases: List<AgentTestCase> = emptyList()

    /** 当前 Suite 的全部用例（含 INVALID，供导出 INVALID 行取原始定义）。 */
    private var allLoadedCases: List<AgentTestCase> = emptyList()

    private var runId: String = UUID.randomUUID().toString()

    /** CR-014：终态执行结果聚合（供导出；页面不二次计算）。 */
    private val executionResults = mutableListOf<TestCaseExecutionResult>()

    /** CR-015：批次启动时冻结的 Suite 指纹（suiteId + schemaVersion + sha256）。 */
    private data class BatchSuiteFingerprint(
        val suiteId: String?,
        val schemaVersion: Int?,
        val sha256: String?
    )

    private var batchFingerprint: BatchSuiteFingerprint? = null

    /** CR-015：批次启动时冻结的用例行模型快照（导出只读该快照）。 */
    private var batchFrozenCases: List<AgentTestCaseUiModel>? = null

    /** CR-015：批次启动时冻结的全部用例定义（含 INVALID，导出 INVALID 行使用）。 */
    private var batchFrozenAllCases: List<AgentTestCase> = emptyList()

    private var exporter: TestResultExporter = DefaultTestResultExporter()

    /** 导出完成后回调（Activity 负责写盘 / 分享）。 */
    var onExportReady: ((ExportedFile) -> Unit)? = null

    /** 接入服务网关、资产仓库与导入器（Activity 绑定服务后调用）。 */
    fun attach(
        gateway: AgentCommandGateway,
        repository: AgentTestCaseRepository,
        importer: AgentTestSuiteImporter? = null,
        caseTimeoutMs: Long = TestBatchRunner.DEFAULT_CASE_TIMEOUT_MS,
        exporter: TestResultExporter = DefaultTestResultExporter()
    ) {
        this.gateway = gateway
        this.repository = repository
        this.importer = importer
        this.exporter = exporter
        this.runner = TestBatchRunner(gateway, caseTimeoutMs)
    }

    /**
     * 加载当前 Suite（IDLE/READY 阶段；Suite 无法解析时显示整体错误且不允许开始）。
     * CR-015：批次运行 / 取消中不重载（Activity 重建后继续当前批次，激活 Suite 在
     * 下次非运行加载时优先生效）。
     */
    fun load() {
        val current = _state.value
        if (current.runState == TestRunState.RUNNING ||
            current.runState == TestRunState.CANCELLING
        ) {
            return
        }
        val repo = repository ?: return
        _state.update { it.copy(runState = TestRunState.LOADING, errorMessage = null) }
        applyLoaded(repo.loadActiveSuite(), runState = TestRunState.READY)
    }

    /** 打开文件选择器前由 Activity 调用（进入 SELECTING 状态）。 */
    fun beginImportSelection() {
        _state.update {
            it.copy(importState = SuiteImportState.SELECTING, importErrorMessage = null)
        }
    }

    /** 用户取消文件选择：视为无变更，不显示失败批次。 */
    fun onImportCancelled() {
        _state.update {
            if (it.importState == SuiteImportState.SELECTING) {
                it.copy(importState = SuiteImportState.IDLE)
            } else {
                it
            }
        }
    }

    /**
     * CR-015：导入并激活外部 Suite JSON（SAF 选择结果）。
     * 批次 RUNNING / CANCELLING、激活中或导出生成中拒绝切换（IVAI-TEST-IMPORT-007）。
     */
    fun importSuite(uri: Uri) {
        val s = _state.value
        if (!canImportNow(s)) {
            rejectImport()
            return
        }
        val currentImporter = importer ?: return
        _state.update { it.copy(importState = SuiteImportState.READING, importErrorMessage = null) }
        viewModelScope.launch {
            handleImportResult(currentImporter.import(uri))
        }
    }

    /**
     * CR-015：直接以字节导入（单元测试与复用管线入口）。与 [importSuite] 共用同一
     * 状态机与结果处理；生产路径由 ContentResolver 读取后进入同一管线。
     */
    internal fun importSuiteBytes(bytes: ByteArray) {
        val s = _state.value
        if (!canImportNow(s)) {
            rejectImport()
            return
        }
        val currentImporter = importer ?: return
        _state.update { it.copy(importState = SuiteImportState.READING, importErrorMessage = null) }
        viewModelScope.launch {
            handleImportResult(currentImporter.importBytes(bytes))
        }
    }

    /** 当前状态是否允许切换 Suite（IVAI-TEST-IMPORT-007）。 */
    private fun canImportNow(s: AgentTestUiState): Boolean =
        s.runState != TestRunState.RUNNING &&
            s.runState != TestRunState.CANCELLING &&
            s.importState != SuiteImportState.READING &&
            s.importState != SuiteImportState.VALIDATING &&
            s.importState != SuiteImportState.ACTIVATING &&
            s.exportState != ExportState.EXPORTING

    private fun rejectImport() {
        _state.update {
            it.copy(
                importState = SuiteImportState.FAILED,
                importErrorMessage = "当前批次或导出状态不允许切换 Suite（${TestErrorCode.IMPORT_BATCH_BLOCKED}）"
            )
        }
    }

    /** 统一处理导入结果：成功 → 刷新页面；失败 → 保持当前 Suite 并提示。 */
    private fun handleImportResult(result: SuiteImportResult) {
        when (result) {
            is SuiteImportResult.Success -> {
                // 激活完成 → 刷新页面列表（Repository 现在优先加载激活文件）。
                _state.update { it.copy(importState = SuiteImportState.ACTIVATING) }
                refreshAfterImport()
            }
            is SuiteImportResult.Failure -> {
                _state.update {
                    it.copy(
                        importState = SuiteImportState.FAILED,
                        importErrorMessage = result.errorMessage
                    )
                }
            }
        }
    }

    /** 开始批次（RUNNING/CANCELLING 时忽略，防止重复批次）。 */
    fun start() {
        val s = _state.value
        if (s.runState == TestRunState.RUNNING || s.runState == TestRunState.CANCELLING) return
        val cases = loadedCases
        if (cases.isEmpty()) return
        runId = UUID.randomUUID().toString()
        executionResults.clear()
        // CR-015：批次启动时冻结 Suite 指纹与用例快照（执行与导出始终使用同一批次）。
        batchFingerprint = BatchSuiteFingerprint(s.suiteId, s.suiteSchemaVersion, s.suiteSha256)
        batchFrozenCases = s.cases
        batchFrozenAllCases = allLoadedCases
        _state.update {
            it.copy(
                runState = TestRunState.RUNNING,
                summary = null,
                errorMessage = null,
                activeCaseId = cases.firstOrNull()?.caseId,
                exportState = ExportState.DISABLED,
                exportFileName = null,
                exportErrorMessage = null,
                importState = SuiteImportState.IDLE,
                importErrorMessage = null,
                cases = it.cases.map { model ->
                    model.copy(
                        status = if (model.status == AgentTestCaseStatus.INVALID) {
                            AgentTestCaseStatus.INVALID
                        } else {
                            AgentTestCaseStatus.PENDING
                        },
                        score = null,
                        scoreTotal = null,
                        terminalStatus = null,
                        errorCode = null,
                        errorMessage = model.errorMessage.takeIf { model.status == AgentTestCaseStatus.INVALID },
                        timing = null
                    )
                }
            )
        }
        batchJob = viewModelScope.launch {
            try {
                runner?.run(
                    runId = runId,
                    cases = cases,
                    onCaseResult = { result -> onCaseResult(result) },
                    onExecutionResult = { exec -> onExecutionResult(exec) },
                    onTimingUpdate = { caseId, timing -> onTimingUpdate(caseId, timing) }
                )?.let { summary ->
                    _state.update {
                        it.copy(
                            runState = TestRunState.COMPLETED,
                            summary = summary,
                            activeCaseId = null,
                            exportState = if (it.cases.isNotEmpty() && it.cases.all { c ->
                                c.status != AgentTestCaseStatus.PENDING && c.status != AgentTestCaseStatus.RUNNING
                            }) ExportState.ENABLED else ExportState.DISABLED
                        )
                    }
                }
            } catch (e: TestRunnerException) {
                // 门禁 / 环境不允许（IVAI-TEST-003）。
                _state.update { it.copy(runState = TestRunState.IDLE, errorMessage = e.message) }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        runState = TestRunState.CANCELLED,
                        activeCaseId = null,
                        summary = partialSummary(it.cases)
                    )
                }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(runState = TestRunState.IDLE, errorMessage = "批次执行异常：${e.message}")
                }
            }
        }
    }

    /** 显式取消批次（首期保留已完成结果，取消活动请求后停止）。 */
    fun cancel() {
        if (_state.value.runState != TestRunState.RUNNING) return
        batchJob?.cancel()
        _state.update { it.copy(runState = TestRunState.CANCELLING) }
    }

    fun toggleExpand(caseId: String) {
        _state.update { st ->
            st.copy(
                cases = st.cases.map {
                    if (it.caseId == caseId) it.copy(expanded = !it.expanded) else it
                }
            )
        }
    }

    /**
     * 导出当前批次 XLSX（CR-014 / CR-015）。仅在批次全部终态时允许；EXPORTING 期间
     * 防止重复触发；成功后通过 [onExportReady] 交 Activity 写盘，失败保留结果并提示。
     * 导出数据来自批次启动时冻结的用例快照 + 终态执行结果，不从当前 Repository 读取。
     */
    fun exportXlsx() {
        val s = _state.value
        if (s.exportState == ExportState.EXPORTING) return
        if (batchFingerprint == null || batchFrozenCases == null) {
            _state.update {
                it.copy(exportState = ExportState.FAILED, exportErrorMessage = "无已启动的批次可导出")
            }
            return
        }
        if (executionResults.isEmpty()) {
            _state.update { it.copy(exportState = ExportState.FAILED, exportErrorMessage = "无已完成的用例可导出") }
            return
        }
        if (executionResults.any { !it.status.isTerminal }) {
            _state.update { it.copy(exportState = ExportState.FAILED, exportErrorMessage = "批次尚未完成，禁止导出") }
            return
        }
        _state.update { it.copy(exportState = ExportState.EXPORTING, exportErrorMessage = null) }
        viewModelScope.launch {
            try {
                // 导出顺序与页面用例顺序一致：按批次冻结快照合并（执行结果 + 加载非法的 INVALID 行）。
                val rows = buildExportRows()
                val file = exporter.exportXlsx(runId, rows)
                _state.update { it.copy(exportState = ExportState.EXPORTED, exportFileName = file.fileName) }
                onExportReady?.invoke(file)
            } catch (e: ExportValidationException) {
                _state.update {
                    it.copy(
                        exportState = ExportState.FAILED,
                        exportErrorMessage = e.message ?: "导出校验失败（${e.errorCode}）"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(exportState = ExportState.FAILED, exportErrorMessage = "导出失败：${e.message}")
                }
            }
        }
    }

    /**
     * 按批次冻结快照组装导出行：执行结果（含 SKIPPED）+ 加载时 INVALID 的用例
     * （无 timing，状态 ERROR）。INVALID 未进入 Runner，必须在此补齐。
     * 不使用当前 Repository / 页面列表，避免导入新 Suite 后旧批次导出错配。
     */
    private fun buildExportRows(): List<TestCaseExecutionResult> {
        val frozen = batchFrozenCases ?: return emptyList()
        val byId = executionResults.associateBy { it.caseId }
        val rows = mutableListOf<TestCaseExecutionResult>()
        for (model in frozen) {
            val exec = byId[model.caseId]
            if (exec != null) {
                rows += exec
            } else if (model.status == AgentTestCaseStatus.INVALID) {
                val case = batchFrozenAllCases.firstOrNull { it.caseId == model.caseId }
                if (case != null) {
                    rows += TestCaseExecutionResult(
                        batchId = runId,
                        caseId = case.caseId,
                        input = case.input,
                        expected = IntentExpectation(
                            level = case.expectedTier.name,
                            domainId = case.expectedDomain.name,
                            capabilityPackId = case.expectedCapabilityPack,
                            targetId = case.expectedTarget?.id,
                            arguments = JsonValueMapper.toValueMap(case.expectedArguments)
                        ),
                        actual = null,
                        status = TestCaseStatus.ERROR,
                        timing = TestCaseTiming(0, null, null, 0, false),
                        failureReason = model.errorMessage ?: "加载时非法，未执行"
                    )
                }
            }
        }
        return rows
    }

    /** 导出完成 / 取消后恢复按钮可用（保留页面结果）。 */
    fun resetExportState() {
        _state.update {
            if (it.exportState == ExportState.EXPORTING) it
            else it.copy(exportState = ExportState.ENABLED, exportErrorMessage = null)
        }
    }

    /** 加载 / 导入激活后统一提交页面状态。 */
    private fun applyLoaded(result: LoadedAgentTestSuite, runState: TestRunState) {
        if (result.hasFatalError) {
            _state.update {
                it.copy(
                    runState = TestRunState.IDLE,
                    suiteId = null,
                    suiteVersion = null,
                    suiteSchemaVersion = null,
                    suiteSource = null,
                    suiteSha256 = null,
                    cases = emptyList(),
                    summary = null,
                    errorMessage = result.errorMessage
                )
            }
            return
        }
        loadedCases = result.validCases
        allLoadedCases = result.verdicts.map { it.case }
        val models = modelsFor(result.verdicts)
        _state.update {
            it.copy(
                runState = runState,
                suiteId = result.suite?.suiteId,
                suiteVersion = result.suite?.governanceVersion,
                suiteSchemaVersion = result.suite?.schemaVersion,
                suiteSource = result.source,
                suiteSha256 = result.sha256,
                cases = models,
                summary = null,
                activeCaseId = null,
                errorMessage = result.degradedReason,
                exportState = ExportState.DISABLED,
                exportFileName = null,
                exportErrorMessage = null
            )
        }
    }

    /**
     * 导入成功后刷新页面状态（CR-015）：一次性提交新的不可变 Suite，清除旧 Suite
     * 的待执行页面状态；已生成的 TestCaseExecutionResult 是原批次不可变快照，导入
     * 后不再展示（创建新的页面测试准备状态）。
     */
    private fun refreshAfterImport() {
        val repo = repository
        if (repo == null) {
            _state.update {
                it.copy(importState = SuiteImportState.FAILED, importErrorMessage = "仓库未就绪，无法刷新")
            }
            return
        }
        val loaded = repo.loadActiveSuite()
        if (loaded.hasFatalError) {
            _state.update {
                it.copy(
                    importState = SuiteImportState.FAILED,
                    importErrorMessage = "激活后重新加载失败：${loaded.errorMessage}"
                )
            }
            return
        }
        loadedCases = loaded.validCases
        allLoadedCases = loaded.verdicts.map { it.case }
        val models = modelsFor(loaded.verdicts)
        _state.update {
            it.copy(
                runState = TestRunState.READY,
                suiteId = loaded.suite?.suiteId,
                suiteVersion = loaded.suite?.governanceVersion,
                suiteSchemaVersion = loaded.suite?.schemaVersion,
                suiteSource = loaded.source,
                suiteSha256 = loaded.sha256,
                cases = models,
                summary = null,
                activeCaseId = null,
                importState = SuiteImportState.SUCCEEDED,
                importErrorMessage = null,
                exportState = ExportState.DISABLED,
                exportFileName = null,
                exportErrorMessage = null
            )
        }
        // 清除旧批次冻结快照与结果（导入创建新的页面测试准备状态）。
        batchFingerprint = null
        batchFrozenCases = null
        batchFrozenAllCases = emptyList()
        executionResults.clear()
    }

    /** 声明顺序的用例行模型（合法 PENDING / 非法 INVALID）。 */
    private fun modelsFor(verdicts: List<CaseVerdict>): List<AgentTestCaseUiModel> = buildList {
        verdicts.forEach { verdict ->
            val case = verdict.case
            add(
                AgentTestCaseUiModel(
                    caseId = case.caseId,
                    input = case.input,
                    description = case.description,
                    enabled = case.enabled,
                    status = if (verdict.isValid) AgentTestCaseStatus.PENDING else AgentTestCaseStatus.INVALID,
                    errorMessage = verdict.invalidReason
                )
            )
        }
    }

    private fun onCaseResult(result: AgentTestCaseResult) {
        _state.update { st ->
            val cases = st.cases.map { model ->
                if (model.caseId == result.caseId) {
                    model.copy(
                        status = result.status,
                        score = result.score,
                        scoreTotal = result.score?.total,
                        terminalStatus = result.terminalStatus,
                        errorCode = result.errorCode,
                        errorMessage = result.errorMessage,
                        timing = result.timing
                    )
                } else {
                    model
                }
            }
            // 标记下一条待执行用例为 RUNNING（串行批次）。
            val nextActive = cases.firstOrNull { it.status == AgentTestCaseStatus.PENDING }?.caseId
            st.copy(
                cases = if (nextActive != null) {
                    cases.map { if (it.caseId == nextActive) it.copy(status = AgentTestCaseStatus.RUNNING) else it }
                } else {
                    cases
                },
                activeCaseId = nextActive
            )
        }
    }

    /** CR-014：终态执行结果聚合（不可变快照，供导出）。 */
    private fun onExecutionResult(exec: TestCaseExecutionResult) {
        executionResults.add(exec)
    }

    /** CR-014：实时展示已落定阶段耗时；未落定阶段保持空（页面显示 "—"）。 */
    private fun onTimingUpdate(caseId: String, timing: TestCaseTiming) {
        _state.update { st ->
            st.copy(
                cases = st.cases.map {
                    if (it.caseId == caseId) it.copy(timing = timing) else it
                }
            )
        }
    }

    /** 取消 / 中断时基于已完成用例计算部分汇总（保留已完成结果）。 */
    private fun partialSummary(cases: List<AgentTestCaseUiModel>): TestRunSummary? {
        val terminal = cases.filter {
            it.status == AgentTestCaseStatus.PASSED ||
                it.status == AgentTestCaseStatus.FAILED ||
                it.status == AgentTestCaseStatus.INCOMPLETE ||
                it.status == AgentTestCaseStatus.TIMEOUT
        }
        if (terminal.isEmpty()) return null
        val executed = terminal.size
        val passed = terminal.count { it.status == AgentTestCaseStatus.PASSED }
        val scoreSum = terminal.sumOf { it.scoreTotal ?: 0 }
        return TestRunSummary(
            executed = executed,
            passed = passed,
            failed = executed - passed,
            skipped = cases.count { it.status == AgentTestCaseStatus.SKIPPED },
            score = scoreSum,
            maxScore = executed * TestBatchRunner.MAX_SCORE_PER_CASE
        )
    }

    override fun onCleared() {
        batchJob?.cancel()
        super.onCleared()
    }
}
