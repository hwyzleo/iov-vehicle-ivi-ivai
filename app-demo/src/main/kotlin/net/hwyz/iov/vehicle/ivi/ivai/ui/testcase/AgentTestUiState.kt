package net.hwyz.iov.vehicle.ivi.ivai.ui.testcase

import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.ExportState
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseStatus
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.TestRunSummary
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring.AgentTestScore
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.timing.TestCaseTiming

/**
 * 批次运行状态（IVI-IVAI-DSN-CR-012 批次状态机）。Runner 内部还细分
 * PREPARE_CASE / CREATE_ISOLATED_SESSION / SUBMITTING / WAITING_RESULT 等
 * 子状态，UI 层只暴露 RUNNING 级别。
 */
enum class TestRunState {
    IDLE,
    LOADING,
    READY,
    RUNNING,
    CANCELLING,
    CANCELLED,
    COMPLETED
}

/**
 * 测试用例列表 UI 状态（IVI-IVAI-DSN-CR-012 / CR-014）。ViewModel 持有批次
 * Job 与内存结果，配置变化不重启批次。
 */
data class AgentTestUiState(
    val suiteId: String? = null,
    val suiteVersion: String? = null,
    val runState: TestRunState = TestRunState.IDLE,
    val cases: List<AgentTestCaseUiModel> = emptyList(),
    val activeCaseId: String? = null,
    val summary: TestRunSummary? = null,
    val errorMessage: String? = null,
    // ---- CR-014 导出状态 ----
    val exportState: ExportState = ExportState.DISABLED,
    val exportFileName: String? = null,
    val exportErrorMessage: String? = null
)

/**
 * 单条用例的 UI 模型。列表默认折叠概要，点击展开预期/实际差异。
 */
data class AgentTestCaseUiModel(
    val caseId: String,
    val input: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val status: AgentTestCaseStatus = AgentTestCaseStatus.PENDING,
    val score: AgentTestScore? = null,
    val scoreTotal: Int? = null,
    val terminalStatus: EvaluationTerminalStatus? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val expanded: Boolean = false,
    // ---- CR-014 分阶段耗时（终态后不可变；实时回调期间为部分计时） ----
    val timing: TestCaseTiming? = null
)
