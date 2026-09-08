package net.hwyz.iov.vehicle.ivi.ivai.ui.testcase

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
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.export.DefaultTestResultExporter
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.export.ExportValidationException
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.export.ExportedFile
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.export.TestResultExporter
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway.AgentCommandGateway
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AgentTestCaseRepository
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
 * 测试用例页 ViewModel（IVI-IVAI-DSN-CR-012 / CR-014）。
 *
 *  - 持有批次 Job 与内存结果，配置变化不重启批次。
 *  - 页面退出但 Activity 未被系统销毁时可继续或取消；首期显式取消并保留已完成结果。
 *  - 进程退出后不恢复未完成批次，重新进入显示未运行。
 *  - 开始按钮在 RUNNING / CANCELLING 状态禁用，防止重复批次（IVAI-TEST-008）。
 *  - CR-014：批次全部终态后才允许导出；导出期间防止重复触发；失败保留结果并提示。
 */
class AgentTestViewModel : ViewModel() {

    private val _state = MutableStateFlow(AgentTestUiState())
    val state: StateFlow<AgentTestUiState> = _state.asStateFlow()

    private var gateway: AgentCommandGateway? = null
    private var repository: AgentTestCaseRepository? = null
    private var runner: TestBatchRunner? = null
    private var batchJob: Job? = null

    private var loadedCases: List<AgentTestCase> = emptyList()
    private var runId: String = UUID.randomUUID().toString()

    /** CR-014：终态执行结果聚合（供导出；页面不二次计算）。 */
    private val executionResults = mutableListOf<TestCaseExecutionResult>()

    private var exporter: TestResultExporter = DefaultTestResultExporter()

    /** 导出完成后回调（Activity 负责写盘 / 分享）。 */
    var onExportReady: ((ExportedFile) -> Unit)? = null

    /** 接入服务网关与资产仓库（Activity 绑定服务后调用）。 */
    fun attach(
        gateway: AgentCommandGateway,
        repository: AgentTestCaseRepository,
        caseTimeoutMs: Long = TestBatchRunner.DEFAULT_CASE_TIMEOUT_MS,
        exporter: TestResultExporter = DefaultTestResultExporter()
    ) {
        this.gateway = gateway
        this.repository = repository
        this.exporter = exporter
        this.runner = TestBatchRunner(gateway, caseTimeoutMs)
    }

    /** 加载本地 Suite（IDLE/READY 阶段；Suite 无法解析时显示整体错误且不允许开始）。 */
    fun load() {
        val repo = repository ?: return
        _state.update { it.copy(runState = TestRunState.LOADING, errorMessage = null) }
        val result = repo.load()
        if (result.hasFatalError) {
            _state.update {
                it.copy(
                    runState = TestRunState.IDLE,
                    suiteId = null,
                    suiteVersion = null,
                    cases = emptyList(),
                    summary = null,
                    errorMessage = result.errorMessage
                )
            }
            return
        }
        loadedCases = result.validCases
        val models = buildList {
            // 保持资产声明顺序：逐条判定，非法用例标记 INVALID 并跳过。
            result.verdicts.forEach { verdict ->
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
        _state.update {
            it.copy(
                runState = TestRunState.READY,
                suiteId = result.suite?.suiteId,
                suiteVersion = result.suite?.governanceVersion,
                cases = models,
                summary = null,
                activeCaseId = null,
                errorMessage = null,
                exportState = ExportState.DISABLED,
                exportFileName = null,
                exportErrorMessage = null
            )
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
        _state.update {
            it.copy(
                runState = TestRunState.RUNNING,
                summary = null,
                errorMessage = null,
                activeCaseId = cases.firstOrNull()?.caseId,
                exportState = ExportState.DISABLED,
                exportFileName = null,
                exportErrorMessage = null,
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
     * 导出当前批次 XLSX（CR-014）。仅在批次全部终态时允许；EXPORTING 期间防止
     * 重复触发；成功后通过 [onExportReady] 交 Activity 写盘，失败保留结果并提示。
     */
    fun exportXlsx() {
        val s = _state.value
        if (s.exportState == ExportState.EXPORTING) return
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
                // 导出顺序与页面用例顺序一致：按 Suite 声明顺序合并（执行结果 + 加载非法的 INVALID 行）。
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
     * 按 Suite 声明顺序组装导出行：执行结果（含 SKIPPED）+ 加载时 INVALID 的用例
     * （无 timing，状态 ERROR）。INVALID 未进入 Runner，必须在此补齐。
     */
    private fun buildExportRows(): List<TestCaseExecutionResult> {
        val byId = executionResults.associateBy { it.caseId }
        val rows = mutableListOf<TestCaseExecutionResult>()
        val state = _state.value
        for (model in state.cases) {
            val exec = byId[model.caseId]
            if (exec != null) {
                rows += exec
            } else if (model.status == AgentTestCaseStatus.INVALID) {
                val case = loadedCases.firstOrNull { it.caseId == model.caseId }
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
