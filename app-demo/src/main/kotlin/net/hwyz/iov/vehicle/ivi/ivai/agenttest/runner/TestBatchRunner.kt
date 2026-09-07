package net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationSnapshotProjector
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway.AgentCommandGateway
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.ActualResultCollector
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring.AgentTestScore
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring.ScoredActual
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring.TestScorer
import net.hwyz.iov.vehicle.ivi.ivai.demo.BuildConfig

/**
 * 单条用例执行状态（IVI-IVAI-DSN-CR-012 批次状态机 / UI 展示）。
 */
enum class AgentTestCaseStatus {
    /** 尚未执行。 */
    PENDING,
    /** 正在执行（RUNNING 子状态）。 */
    RUNNING,
    /** 单条得分 5。 */
    PASSED,
    /** 单条得分 0～4（UI 保留具体分值）。 */
    FAILED,
    /** 结果不完整（NEED_DIALOGUE / WAITING_CONFIRMATION）。 */
    INCOMPLETE,
    /** 单条等待结果超时（IVAI-TEST-005）。 */
    TIMEOUT,
    /** 加载时非法（IVAI-TEST-002），跳过。 */
    INVALID,
    /** enabled=false，跳过。 */
    SKIPPED
}

/**
 * 单条用例执行结果（IVI-IVAI-DSN-CR-012）。
 */
data class AgentTestCaseResult(
    val runId: String,
    val caseId: String,
    val status: AgentTestCaseStatus,
    val score: AgentTestScore? = null,
    val terminalStatus: EvaluationTerminalStatus? = null,
    val reasonCode: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

/**
 * 批次汇总（IVI-IVAI-DSN-CR-012）。passed 定义为单条得分 5；0～4 计入 failed；
 * maxScore = executed × 5，跳过用例不计入满分。
 */
data class TestRunSummary(
    val executed: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val score: Int,
    val maxScore: Int
) {
    val scoreRate: Double
        get() = if (maxScore == 0) 0.0 else score.toDouble() / maxScore
}

/** 批次级异常（门禁 / 非法状态转换）。 */
class TestRunnerException(
    val errorCode: String,
    message: String
) : RuntimeException(message)

/**
 * 串行批次执行器（IVI-IVAI-DSN-CR-012 批次状态机）。
 *
 *  - 同一时刻只有一个活动 case；收到该 requestId 的终态后才执行下一条。
 *  - 每条用例创建隔离的测试 Session（REQ-121）。
 *  - 与聊天发送走同一 [AgentCommandGateway] → AiAgentClient.submit 入口。
 *  - NEED_DIALOGUE / WAITING_CONFIRMATION 不自动补答或确认，按结果不完整结束。
 *  - 单条超时（[caseTimeoutMs]）后取消活动请求（[AgentCommandGateway.cancelRequest]）
 *    并继续下一条。
 *  - 事件按 requestId 去重，不重复评分和提交；旧批次事件不更新新批次（Session 隔离）。
 *  - 启动时执行运行环境门禁（Release / 真实 Binding 拒绝，IVAI-TEST-003）。
 */
class TestBatchRunner(
    private val gateway: AgentCommandGateway,
    private val caseTimeoutMs: Long = DEFAULT_CASE_TIMEOUT_MS,
    private val awaitIdleTimeoutMs: Long = DEFAULT_AWAIT_IDLE_TIMEOUT_MS,
    private val environmentAllowed: () -> Boolean = { BuildConfig.DEBUG }
) {

    /**
     * 顺序执行 [cases]。每完成一条通过 [onCaseResult] 回调；执行被取消（外层
     * Job cancel）时中断并取消活动请求。
     *
     * @return 批次汇总。
     */
    suspend fun run(
        runId: String,
        cases: List<AgentTestCase>,
        onCaseResult: suspend (AgentTestCaseResult) -> Unit
    ): TestRunSummary {
        if (!environmentAllowed()) {
            throw TestRunnerException(
                TestErrorCode.ENVIRONMENT_FORBIDDEN,
                "当前构建或 Binding 环境不允许批量测试（仅 Debug/Test + Mock Adapter）"
            )
        }
        var executed = 0
        var passed = 0
        var failed = 0
        var skipped = 0
        var scoreSum = 0

        for (case in cases) {
            if (!case.enabled) {
                skipped++
                onCaseResult(
                    AgentTestCaseResult(
                        runId = runId,
                        caseId = case.caseId,
                        status = AgentTestCaseStatus.SKIPPED
                    )
                )
                continue
            }

            val result = runOne(runId, case)
            when (result.status) {
                AgentTestCaseStatus.PASSED -> passed++
                AgentTestCaseStatus.SKIPPED -> skipped++
                else -> failed++
            }
            scoreSum += result.score?.total ?: 0
            onCaseResult(result)
            executed++
        }

        return TestRunSummary(
            executed = executed,
            passed = passed,
            failed = failed,
            skipped = skipped,
            score = scoreSum,
            maxScore = executed * MAX_SCORE_PER_CASE
        )
    }

    /** 执行单条用例：等闸门释放 → 隔离 Session → 提交 → 等待终态 → 取快照 → 评分。 */
    private suspend fun runOne(runId: String, case: AgentTestCase): AgentTestCaseResult {
        // 串行语义加固：终态事件在服务端 process 内部发出，而活动 Turn 闸门要等 process
        // 完全返回（finally）才释放——事件到达 ≠ 闸门释放。提交下一条前必须先等闸门真正
        // 空闲，否则全局单飞闸门会拒绝后续所有提交（大量 IVAI-TEST-004 批量失败）。
        if (!gateway.awaitIdle(awaitIdleTimeoutMs)) {
            // 可能是阻塞模型调用未被取消打断：强制取消当前活动请求后重试一次。
            gateway.cancelActiveRequest()
            if (!gateway.awaitIdle(awaitIdleTimeoutMs)) {
                return AgentTestCaseResult(
                    runId = runId,
                    caseId = case.caseId,
                    status = AgentTestCaseStatus.FAILED,
                    terminalStatus = EvaluationTerminalStatus.FAILED,
                    errorCode = TestErrorCode.SERVICE_BUSY,
                    errorMessage = "Agent 服务活动 Turn 持续未释放，等待并强制取消后仍无法提交（IVAI-TEST-009）"
                )
            }
        }

        val requestId = UUID.randomUUID().toString()
        val sessionId = gateway.createTestSession()
        val collector = ActualResultCollector(requestId)
        val terminal = CompletableDeferred<Unit>()

        return coroutineScope {
            val collectJob = launch {
                try {
                    gateway.observe(sessionId).collect { event ->
                        if (collector.onEvent(event)) terminal.complete(Unit)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    terminal.completeExceptionally(e)
                }
            }
            try {
                val accepted = gateway.submitText(sessionId, requestId, case.input)
                if (!accepted) {
                    return@coroutineScope AgentTestCaseResult(
                        runId = runId,
                        caseId = case.caseId,
                        status = AgentTestCaseStatus.FAILED,
                        terminalStatus = EvaluationTerminalStatus.FAILED,
                        errorCode = TestErrorCode.SUBMIT_FAILED,
                        errorMessage = "提交失败：Agent 服务不可用（Agent 图未构建或服务未就绪）"
                    )
                }

                val finishedInTime = withTimeoutOrNull(caseTimeoutMs) { terminal.await() } != null
                if (!finishedInTime) {
                    gateway.cancelRequest(requestId)
                    return@coroutineScope AgentTestCaseResult(
                        runId = runId,
                        caseId = case.caseId,
                        status = AgentTestCaseStatus.TIMEOUT,
                        terminalStatus = EvaluationTerminalStatus.TIMEOUT,
                        errorCode = TestErrorCode.CASE_TIMEOUT,
                        errorMessage = "等待结果超时（${caseTimeoutMs}ms），已取消该请求"
                    )
                }

                // 结构化实际结果以快照契约为权威来源；缺失时用采集器兜底（IVAI-TEST-006）。
                // 终态事件与快照写入之间仍存在极小竞态窗口（workflow 已改为先写快照再发
                // 终态；此处再做有界重试兜底，避免 006 型 RESULT_MISSING）。
                var snapshot = gateway.evaluationSnapshot(requestId)
                var snapshotMissing = snapshot == null
                if (snapshotMissing) {
                    repeat(SNAPSHOT_READ_RETRY_TIMES) {
                        delay(SNAPSHOT_READ_RETRY_INTERVAL_MS)
                        snapshot = gateway.evaluationSnapshot(requestId)
                        if (snapshot != null) {
                            snapshotMissing = false
                            return@repeat
                        }
                    }
                }
                val actual = snapshot?.let {
                    ScoredActual(
                        finalTier = it.finalTier,
                        finalDomain = it.finalDomain,
                        actualCapabilityPacks = it.selectedCapabilityPackIds,
                        target = it.selectedTarget,
                        arguments = it.normalizedArguments
                    )
                } ?: ScoredActual()
                val score = TestScorer.score(case, actual)

                val terminalStatus = snapshot?.terminalStatus
                    ?: collector.terminalStatus
                    ?: EvaluationTerminalStatus.FAILED
                val status = when {
                    snapshotMissing -> AgentTestCaseStatus.FAILED
                    EvaluationSnapshotProjector.isIncomplete(terminalStatus) -> AgentTestCaseStatus.INCOMPLETE
                    score.total == MAX_SCORE_PER_CASE -> AgentTestCaseStatus.PASSED
                    else -> AgentTestCaseStatus.FAILED
                }
                AgentTestCaseResult(
                    runId = runId,
                    caseId = case.caseId,
                    status = status,
                    score = score,
                    terminalStatus = terminalStatus,
                    reasonCode = snapshot?.reasonCode,
                    errorCode = if (snapshotMissing) TestErrorCode.RESULT_MISSING else collector.errorCode,
                    errorMessage = if (snapshotMissing) "结构化实际结果缺失或无法关联 requestId" else null
                )
            } catch (e: CancellationException) {
                // 批次取消（外层 Job cancel）：取消活动请求后中断（CANCELLING → CANCELLED）。
                gateway.cancelRequest(requestId)
                throw e
            } finally {
                collectJob.cancel()
            }
        }
    }

    companion object {
        const val DEFAULT_CASE_TIMEOUT_MS = 30_000L

        /** 提交下一条前等待服务端释放活动 Turn 的超时（串行语义加固）。 */
        const val DEFAULT_AWAIT_IDLE_TIMEOUT_MS = 5_000L

        /** 终态事件与快照写入竞态兜底：重试次数与间隔（合计约 1s）。 */
        const val SNAPSHOT_READ_RETRY_TIMES = 20
        const val SNAPSHOT_READ_RETRY_INTERVAL_MS = 50L

        const val MAX_SCORE_PER_CASE = 5
    }
}
