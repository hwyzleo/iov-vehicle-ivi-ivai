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
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway.AgentCommandGateway
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AgentTestCaseRepository
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseResult
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseStatus
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.TestBatchRunner
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.TestRunnerException
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.TestRunSummary

/**
 * 测试用例页 ViewModel（IVI-IVAI-DSN-CR-012）。
 *
 *  - 持有批次 Job 与内存结果，配置变化不重启批次。
 *  - 页面退出但 Activity 未被系统销毁时可继续或取消；首期显式取消并保留已完成结果。
 *  - 进程退出后不恢复未完成批次，重新进入显示未运行。
 *  - 开始按钮在 RUNNING / CANCELLING 状态禁用，防止重复批次（IVAI-TEST-008）。
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

    /** 接入服务网关与资产仓库（Activity 绑定服务后调用）。 */
    fun attach(
        gateway: AgentCommandGateway,
        repository: AgentTestCaseRepository,
        caseTimeoutMs: Long = TestBatchRunner.DEFAULT_CASE_TIMEOUT_MS
    ) {
        this.gateway = gateway
        this.repository = repository
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
                errorMessage = null
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
        _state.update {
            it.copy(
                runState = TestRunState.RUNNING,
                summary = null,
                errorMessage = null,
                activeCaseId = cases.firstOrNull()?.caseId,
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
                        errorMessage = model.errorMessage.takeIf { model.status == AgentTestCaseStatus.INVALID }
                    )
                }
            )
        }
        batchJob = viewModelScope.launch {
            try {
                runner?.run(runId, cases) { result -> onCaseResult(result) }?.let { summary ->
                    _state.update {
                        it.copy(
                            runState = TestRunState.COMPLETED,
                            summary = summary,
                            activeCaseId = null
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
                        errorMessage = result.errorMessage
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
