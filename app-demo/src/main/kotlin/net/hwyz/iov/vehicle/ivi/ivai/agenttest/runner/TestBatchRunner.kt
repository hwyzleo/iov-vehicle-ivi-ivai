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
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.CaseDiagnostics
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.TestResultDiagnostics
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway.AgentCommandGateway
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.IntentActualResult
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.IntentExpectation
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.canonical.ParameterCanonicalizer
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.JsonValueMapper
import kotlinx.serialization.json.JsonObject
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.TestCaseExecutionResult
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.TestCaseStatus
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring.AgentTestScore
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring.ScoredActual
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring.TestScorer
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.timing.DefaultTestCaseTimingCollector
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.timing.TestCaseTiming
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.timing.TestCaseTimingCollector
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.ActualResultCollector
import net.hwyz.iov.vehicle.ivi.ivai.demo.BuildConfig
import net.hwyz.iov.vehicle.ivi.ivai.model.lifecycle.ModelTimeoutPolicy
import net.hwyz.iov.vehicle.ivi.ivai.model.lifecycle.ProviderCleanupBarrier

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
 * 单条用例执行结果（IVI-IVAI-DSN-CR-012 / CR-014）。
 *
 * [timing] 为 CR-014 新增的分阶段耗时（终态后不可变；未执行用例为空）。
 */
data class AgentTestCaseResult(
    val runId: String,
    val caseId: String,
    val status: AgentTestCaseStatus,
    val score: AgentTestScore? = null,
    val terminalStatus: EvaluationTerminalStatus? = null,
    val reasonCode: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val timing: TestCaseTiming? = null,
    /** CR-014：该用例在终态时投影出的结构化实际值（供导出组装）。 */
    val executionActual: ScoredActual? = null,
    /** CR-016：故障定位辅助字段（terminalStage / failureReason / 候选 Hash / 规则与参数来源）。 */
    val diagnostics: CaseDiagnostics? = null
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
 * 串行批次执行器（IVI-IVAI-DSN-CR-012 批次状态机 / CR-014 计时采集）。
 *
 *  - 同一时刻只有一个活动 case；收到该 requestId 的终态后才执行下一条。
 *  - 每条用例创建隔离的测试 Session（REQ-121）。
 *  - 与聊天发送走同一 [AgentCommandGateway] → AiAgentClient.submit 入口。
 *  - NEED_DIALOGUE / WAITING_CONFIRMATION 不自动补答或确认，按结果不完整结束。
 *  - 单条超时（[caseTimeoutMs]）后取消活动请求（[AgentCommandGateway.cancelRequest]）
 *    并继续下一条。
 *  - 事件按 requestId 去重，不重复评分和提交；旧批次事件不更新新批次（Session 隔离）。
 *  - 启动时执行运行环境门禁（Release / 真实 Binding 拒绝，IVAI-TEST-003）。
 *
 *  CR-014 计时：每条用例以单调时钟记录 t0 调度 → t1 处理 → t2/t3/t4 LLM →
 *  t5 终态；超时、取消和异常在 finally 路径落定 t5 并保留已采集里程碑。
 */
class TestBatchRunner(
    private val gateway: AgentCommandGateway,
    private val caseTimeoutMs: Long = DEFAULT_CASE_TIMEOUT_MS,
    private val awaitIdleTimeoutMs: Long = DEFAULT_AWAIT_IDLE_TIMEOUT_MS,
    private val environmentAllowed: () -> Boolean = { BuildConfig.DEBUG },
    private val timingCollector: TestCaseTimingCollector = DefaultTestCaseTimingCollector(),
    /** CR-016: Provider 清理屏障（下一条开始前确认上一条清理完成或达到独立超时）。 */
    private val cleanupBarrier: ProviderCleanupBarrier = ProviderCleanupBarrier(
        ModelTimeoutPolicy.DEFAULT.cleanupTimeoutMs
    ),
    /** CR-016: 连续 Provider 失败达到阈值后有限指数退避，不无限重试。 */
    private val consecutiveFailureThreshold: Int = DEFAULT_CONSECUTIVE_FAILURE_THRESHOLD,
    private val retryBackoffBaseMs: Long = DEFAULT_RETRY_BACKOFF_BASE_MS,
    private val retryBackoffMaxMs: Long = DEFAULT_RETRY_BACKOFF_MAX_MS
) {

    /**
     * 顺序执行 [cases]。每完成一条通过 [onCaseResult] 回调；执行被取消（外层
     * Job cancel）时中断并取消活动请求。
     *
     * @param onExecutionResult CR-014：每条用例终态时的不可变执行结果（供导出 /
     *   批次状态聚合；包含 expected/actual/timing）。
     * @param onTimingUpdate CR-014：用例执行过程中的部分计时实时回调（页面展示
     *   已落定阶段耗时；未落定阶段保持空）。
     * @return 批次汇总。
     */
    suspend fun run(
        runId: String,
        cases: List<AgentTestCase>,
        onExecutionResult: (suspend (TestCaseExecutionResult) -> Unit)? = null,
        onTimingUpdate: ((String, TestCaseTiming) -> Unit)? = null,
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
        var consecutiveFailures = 0

        for (case in cases) {
            if (!case.enabled) {
                skipped++
                val result = AgentTestCaseResult(
                    runId = runId,
                    caseId = case.caseId,
                    status = AgentTestCaseStatus.SKIPPED
                )
                onCaseResult(result)
                onExecutionResult?.invoke(toExecutionResult(runId, case, result, actual = null))
                continue
            }

            // CR-016: 下一条开始前等待上一条 Provider 清理确认（或独立清理超时）。
            val cleaned = cleanupBarrier.awaitCleanup()
            if (!cleaned) {
                // 清理未确认：记录 IVAI-MODEL-CLEANUP-001（可继续，避免批次卡死）。
                val cleanupResult = AgentTestCaseResult(
                    runId = runId,
                    caseId = case.caseId,
                    status = AgentTestCaseStatus.FAILED,
                    terminalStatus = EvaluationTerminalStatus.FAILED,
                    errorCode = "IVAI-MODEL-CLEANUP-001",
                    errorMessage = "上一条 Provider 资源清理未在限定时间确认（IVAI-MODEL-CLEANUP-001）"
                )
                onCaseResult(cleanupResult)
                onExecutionResult?.invoke(toExecutionResult(runId, case, cleanupResult, actual = null))
                failed++
                executed++
                continue
            }

            // CR-016: 连续失败达到阈值 → 有限指数退避（健康检查窗口），不无限重试。
            if (consecutiveFailures >= consecutiveFailureThreshold) {
                val backoffMs = (retryBackoffBaseMs shl (consecutiveFailures - consecutiveFailureThreshold))
                    .coerceAtMost(retryBackoffMaxMs)
                delay(backoffMs)
            }

            val result = runOne(runId, case, onTimingUpdate)
            // CR-016: 每条用例结束后确认 Provider 清理（终态后进入清理确认阶段）。
            cleanupBarrier.markCleaned()
            consecutiveFailures = if (result.status == AgentTestCaseStatus.PASSED) {
                0
            } else {
                consecutiveFailures + 1
            }
            when (result.status) {
                AgentTestCaseStatus.PASSED -> passed++
                AgentTestCaseStatus.SKIPPED -> skipped++
                else -> failed++
            }
            scoreSum += result.score?.total ?: 0
            onCaseResult(result)
            onExecutionResult?.invoke(toExecutionResult(runId, case, result, actual = result.executionActual))
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
    private suspend fun runOne(
        runId: String,
        case: AgentTestCase,
        onTimingUpdate: ((String, TestCaseTiming) -> Unit)?
    ): AgentTestCaseResult {
        timingCollector.onCaseScheduled(case.caseId)

        // 串行语义加固：终态事件在服务端 process 内部发出，而活动 Turn 闸门要等 process
        // 完全返回（finally）才释放——事件到达 ≠ 闸门释放。提交下一条前必须先等闸门真正
        // 空闲，否则全局单飞闸门会拒绝后续所有提交（大量 IVAI-TEST-004 批量失败）。
        if (!gateway.awaitIdle(awaitIdleTimeoutMs)) {
            // 可能是阻塞模型调用未被取消打断：强制取消当前活动请求后重试一次。
            gateway.cancelActiveRequest()
            if (!gateway.awaitIdle(awaitIdleTimeoutMs)) {
                val timing = timingCollector.onCaseFinalized(case.caseId)
                return AgentTestCaseResult(
                    runId = runId,
                    caseId = case.caseId,
                    status = AgentTestCaseStatus.FAILED,
                    terminalStatus = EvaluationTerminalStatus.FAILED,
                    errorCode = TestErrorCode.SERVICE_BUSY,
                    errorMessage = "Agent 服务活动 Turn 持续未释放，等待并强制取消后仍无法提交（IVAI-TEST-009）",
                    timing = timing
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
                        // CR-014：验证链路事件驱动计时里程碑（t1～t4）。
                        onTimingEvent(case.caseId, event, onTimingUpdate)
                        if (collector.onEvent(event)) terminal.complete(Unit)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    terminal.completeExceptionally(e)
                }
            }
            var result: AgentTestCaseResult
            var snapshot: net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot? = null
            try {
                val accepted = gateway.submitText(sessionId, requestId, case.input)
                if (!accepted) {
                    val timing = timingCollector.onCaseFinalized(case.caseId)
                    result = AgentTestCaseResult(
                        runId = runId,
                        caseId = case.caseId,
                        status = AgentTestCaseStatus.FAILED,
                        terminalStatus = EvaluationTerminalStatus.FAILED,
                        errorCode = TestErrorCode.SUBMIT_FAILED,
                        errorMessage = "提交失败：Agent 服务不可用（Agent 图未构建或服务未就绪）",
                        timing = timing
                    )
                } else {
                    val finishedInTime = withTimeoutOrNull(caseTimeoutMs) { terminal.await() } != null
                    if (!finishedInTime) {
                        gateway.cancelRequest(requestId)
                        val timing = timingCollector.onCaseFinalized(case.caseId)
                        result = AgentTestCaseResult(
                            runId = runId,
                            caseId = case.caseId,
                            status = AgentTestCaseStatus.TIMEOUT,
                            terminalStatus = EvaluationTerminalStatus.TIMEOUT,
                            errorCode = TestErrorCode.CASE_TIMEOUT,
                            errorMessage = "等待结果超时（${caseTimeoutMs}ms），已取消该请求",
                            timing = timing
                        )
                    } else {
                        // 结构化实际结果以快照契约为权威来源；缺失时用采集器兜底（IVAI-TEST-006）。
                        // 终态事件与快照写入之间仍存在极小竞态窗口（workflow 已改为先写快照再发
                        // 终态；此处再做有界重试兜底，避免 006 型 RESULT_MISSING）。
                        snapshot = gateway.evaluationSnapshot(requestId)
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
                                arguments = it.normalizedArguments,
                                // CR-019：终态与 reasonCode（Outcome 评分依据）。
                                terminalStatus = it.terminalStatus,
                                reasonCode = it.reasonCode
                            )
                        } ?: ScoredActual()
                        val score = TestScorer.score(case, actual)

                        val terminalStatus = snapshot?.terminalStatus
                            ?: collector.terminalStatus
                            ?: EvaluationTerminalStatus.FAILED
                        val status = when {
                            snapshotMissing -> AgentTestCaseStatus.FAILED
                            // CR-019 V2：业务 Outcome 显式断言（EXECUTE/NEED_DIALOGUE/
                            // REJECTED）→ 以评分门槛为准；NEED_DIALOGUE/REJECTED 是预期
                            // 业务终态，不得再被当作 INCOMPLETE 失败。
                            case.expectedOutcome != null ->
                                if (score.passed) AgentTestCaseStatus.PASSED else AgentTestCaseStatus.FAILED
                            EvaluationSnapshotProjector.isIncomplete(terminalStatus) -> AgentTestCaseStatus.INCOMPLETE
                            score.passed -> AgentTestCaseStatus.PASSED
                            else -> AgentTestCaseStatus.FAILED
                        }
                        val timing = timingCollector.onCaseFinalized(case.caseId)
                        result = AgentTestCaseResult(
                            runId = runId,
                            caseId = case.caseId,
                            status = status,
                            score = score,
                            terminalStatus = terminalStatus,
                            reasonCode = snapshot?.reasonCode,
                            errorCode = if (snapshotMissing) TestErrorCode.RESULT_MISSING else collector.errorCode,
                            errorMessage = if (snapshotMissing) "结构化实际结果缺失或无法关联 requestId" else null,
                            timing = timing,
                            executionActual = if (snapshotMissing) null else actual,
                            diagnostics = TestResultDiagnostics.project(snapshot)
                        )
                    }
                }
            } catch (e: CancellationException) {
                // 批次取消（外层 Job cancel）：取消活动请求后中断（CANCELLING → CANCELLED）。
                gateway.cancelRequest(requestId)
                timingCollector.onCaseFinalized(case.caseId)
                throw e
            } catch (e: Exception) {
                // 异常也必须落定 t5（保留已采集里程碑），随后转失败结果。
                val timing = timingCollector.onCaseFinalized(case.caseId)
                result = AgentTestCaseResult(
                    runId = runId,
                    caseId = case.caseId,
                    status = AgentTestCaseStatus.FAILED,
                    terminalStatus = EvaluationTerminalStatus.FAILED,
                    errorCode = TestErrorCode.SUBMIT_FAILED,
                    errorMessage = "用例执行异常：${e.message}",
                    timing = timing
                )
            } finally {
                collectJob.cancel()
            }
            result
        }
    }

    /** CR-014：把验证链路事件映射为计时里程碑（t1～t4），并触发实时回调。 */
    private fun onTimingEvent(
        caseId: String,
        event: AgentEvent,
        onTimingUpdate: ((String, TestCaseTiming) -> Unit)?
    ) {
        when (event) {
            is AgentEvent.ProcessingStarted -> timingCollector.onProcessingStarted(caseId)
            is AgentEvent.ModelCallStarted -> timingCollector.onLlmRequestStarted(
                caseId, event.requestId, event.model, event.providerType
            )
            is AgentEvent.StreamingDelta -> {
                // 首个可消费文本增量（非空）才计首字；采集器内部保证只写一次。
                if (event.text.isNotBlank()) {
                    timingCollector.onLlmFirstConsumableOutput(caseId, event.requestId)
                }
            }
            is AgentEvent.ModelCallCompleted -> timingCollector.onLlmCompleted(
                caseId, event.requestId, event.model, event.providerType
            )
            else -> {}
        }
        if (onTimingUpdate != null) {
            timingCollector.partialTiming(caseId)?.let { onTimingUpdate(caseId, it) }
        }
    }

    /** CR-014：由用例定义 + 执行结果投影不可变 [TestCaseExecutionResult]。 */
    private fun toExecutionResult(
        runId: String,
        case: AgentTestCase,
        result: AgentTestCaseResult,
        actual: ScoredActual?
    ): TestCaseExecutionResult {
        // CR-019：参数展示与评分使用同一个 Schema-aware Comparator 的 canonical 值
        // （数值 5 与 5.0 等价，导出列不得显示不一致，IVAI-TEST-COMPARATOR-001）。
        val expectedArgs = ParameterCanonicalizer.canonicalForDisplay(case.expectedArguments) as? JsonObject
        val actualArgs = actual?.arguments
            ?.let { ParameterCanonicalizer.canonicalForDisplay(it) as? JsonObject }
        val expected = IntentExpectation(
            level = case.expectedTier.name,
            domainId = case.expectedDomain.name,
            capabilityPackId = case.expectedCapabilityPack,
            targetId = case.expectedTarget?.id,
            arguments = JsonValueMapper.toValueMap(expectedArgs),
            // CR-019：V2 业务 Outcome 与 reasonCode 期望。
            expectedOutcome = case.expectedOutcome?.name,
            expectedReasonCode = case.expectedReasonCode
        )
        val actualResult = actual?.let {
            IntentActualResult(
                level = it.finalTier?.name,
                domainId = it.finalDomain?.name,
                capabilityPackId = it.actualCapabilityPacks.firstOrNull(),
                targetId = it.target?.id,
                arguments = actualArgs?.let(JsonValueMapper::toValueMap) ?: emptyMap(),
                // CR-019：V2 业务 Outcome 与终态 reasonCode。
                actualOutcome = it.runtimeOutcome?.name,
                reasonCode = it.reasonCode
            )
        }
        val timing = result.timing ?: TestCaseTiming(
            processingStartLatencyMs = 0,
            llmFirstTokenLatencyMs = null,
            llmCompleteLatencyMs = null,
            totalCaseDurationMs = 0,
            llmInvoked = false
        )
        return TestCaseExecutionResult(
            batchId = runId,
            caseId = case.caseId,
            input = case.input,
            expected = expected,
            actual = actualResult,
            status = mapStatus(result.status),
            timing = timing,
            failureReason = result.errorMessage ?: result.errorCode,
            // CR-018：运行时失败与评分差异拆分诊断（导出可选列）。
            diagnostics = buildFailureDiagnostics(result, actual)
        )
    }

    /**
     * CR-018：从用例执行结果投影 [TestFailureDiagnostics]。
     * 运行时成功但评分不匹配只记为 score mismatch（isScoreMismatchOnly），
     * 不得伪装为运行时失败；快照与评分均缺失时置 IVAI-SCORE-DIAG-001。
     */
    private fun buildFailureDiagnostics(
        result: AgentTestCaseResult,
        actual: ScoredActual?
    ): net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.TestFailureDiagnostics {
        val diag = result.diagnostics
        val scoreStatus = when {
            result.score == null -> net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.ScoreStatus.NOT_SCORED
            result.score.passed ->
                net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.ScoreStatus.PASSED
            else -> net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.ScoreStatus.FAILED
        }
        val mismatchDimensions = result.score?.let { score ->
            buildSet {
                if (!score.tier.matched) add(net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.ScoreDimension.TIER)
                if (!score.domain.matched) add(net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.ScoreDimension.DOMAIN)
                if (!score.capabilityPack.matched) add(net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.ScoreDimension.CAPABILITY_PACK)
                if (!score.target.matched) add(net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.ScoreDimension.TARGET)
                if (!score.arguments.matched) add(net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.ScoreDimension.ARGUMENTS)
                // CR-019：V2 Outcome 独立评分维度。
                if (score.outcome?.matched == false) {
                    add(net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.ScoreDimension.OUTCOME)
                }
            }
        } ?: emptySet()
        val mismatchDetail = result.score?.let { score ->
            listOfNotNull(
                score.tier.reason, score.domain.reason, score.capabilityPack.reason,
                score.target.reason, score.arguments.reason, score.outcome?.reason
            ).ifEmpty { null }?.joinToString("；")
        }
        val runtime = net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.TestResultDiagnostics.runtimeStatusOf(result.terminalStatus)
        val domainEvidence = listOfNotNull(
            diag?.initialDomains?.joinToString(",")?.takeIf { it.isNotBlank() },
            diag?.finalDomain?.let { "最终=$it" }
        ).joinToString(" → ")
        val ragTopK = diag?.let {
            it.retrievedCandidateIds.zip(it.retrievedCandidateScores)
                .joinToString(";") { (id, s) -> "$id:%.3f".format(s) }
        }
        val selected = actual?.target?.id?.let { id ->
            val topScore = diag?.retrievedCandidateScores?.firstOrNull()
            topScore?.let { "$id:%.3f".format(it) } ?: id
        }
        return net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.TestFailureDiagnostics(
            runtimeStatus = runtime,
            runtimeTerminalStage = diag?.terminalStage,
            runtimeReasonCode = result.reasonCode ?: result.errorCode,
            scoreStatus = scoreStatus,
            mismatchDimensions = mismatchDimensions,
            mismatchDetail = mismatchDetail,
            domainEvidence = domainEvidence?.takeIf { it.isNotBlank() },
            ragTopK = ragTopK?.takeIf { it.isNotBlank() },
            selectedCandidate = selected
        )
    }

    /** CR-012 状态 → CR-014 导出状态。 */
    private fun mapStatus(status: AgentTestCaseStatus): TestCaseStatus = when (status) {
        AgentTestCaseStatus.PENDING -> TestCaseStatus.PENDING
        AgentTestCaseStatus.RUNNING -> TestCaseStatus.RUNNING
        AgentTestCaseStatus.PASSED -> TestCaseStatus.PASSED
        AgentTestCaseStatus.FAILED -> TestCaseStatus.FAILED
        AgentTestCaseStatus.INCOMPLETE -> TestCaseStatus.FAILED
        AgentTestCaseStatus.TIMEOUT -> TestCaseStatus.TIMED_OUT
        AgentTestCaseStatus.INVALID -> TestCaseStatus.ERROR
        AgentTestCaseStatus.SKIPPED -> TestCaseStatus.SKIPPED
    }

    companion object {
        const val DEFAULT_CASE_TIMEOUT_MS = 30_000L

        /** 提交下一条前等待服务端释放活动 Turn 的超时（串行语义加固）。 */
        const val DEFAULT_AWAIT_IDLE_TIMEOUT_MS = 5_000L

        /** CR-016: 连续失败达到阈值后进入有限指数退避（健康检查窗口）。 */
        const val DEFAULT_CONSECUTIVE_FAILURE_THRESHOLD = 3
        const val DEFAULT_RETRY_BACKOFF_BASE_MS = 500L
        const val DEFAULT_RETRY_BACKOFF_MAX_MS = 4_000L

        /** 终态事件与快照写入竞态兜底：重试次数与间隔（合计约 1s）。 */
        const val SNAPSHOT_READ_RETRY_TIMES = 20
        const val SNAPSHOT_READ_RETRY_INTERVAL_MS = 50L

        const val MAX_SCORE_PER_CASE = 5
    }
}
