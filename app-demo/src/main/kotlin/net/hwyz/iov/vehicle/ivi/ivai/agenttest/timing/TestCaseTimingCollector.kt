package net.hwyz.iov.vehicle.ivi.ivai.agenttest.timing

import java.util.concurrent.ConcurrentHashMap

/**
 * 单条用例的分阶段耗时（IVI-IVAI-DSN-CR-014 执行结果模型）。
 *
 * 统一里程碑：
 * ```
 * t0 CASE_SCHEDULED → t1 PROCESSING_STARTED → [t2 LLM_REQUEST_STARTED
 *   → t3 LLM_FIRST_CONSUMABLE_OUTPUT → t4 LLM_RESPONSE_COMPLETED] → t5 CASE_RESULT_FINALIZED
 * ```
 * 口径：
 * - processingStartLatencyMs = t1 - t0（必填）。
 * - llmFirstTokenLatencyMs   = t3 - t2（首字；未调用 LLM 时为 null，不得填 0）。
 * - llmCompleteLatencyMs     = t4 - t2（完整返回；未调用 LLM 时为 null）。
 * - totalCaseDurationMs      = t5 - t0（必填）。
 * - llmInvoked=false 时两个 LLM 耗时必须为 null。
 *
 * 多次 LLM 请求时：用例级首字耗时取首次请求发起至首次可消费输出；完整返回耗时取
 * 首次请求发起至最后一次必要响应完成。请求级 Trace 单独保留（[requestTrace]）。
 */
data class TestCaseTiming(
    val processingStartLatencyMs: Long,
    val llmFirstTokenLatencyMs: Long?,
    val llmCompleteLatencyMs: Long?,
    val totalCaseDurationMs: Long,
    val llmInvoked: Boolean
)

/**
 * 单条 LLM 请求的请求级 Trace（IVI-IVAI-DSN-CR-014）。用于后续分析重试或多轮
 * 调用；Excel 必选列输出用例级汇总值。
 */
data class LlmRequestTrace(
    val requestId: String,
    val startedAtMs: Long,
    val firstConsumableOutputAtMs: Long? = null,
    val completedAtMs: Long? = null,
    val model: String? = null,
    val providerType: String? = null
)

/**
 * 统一的测试用例计时采集器（IVI-IVAI-DSN-CR-014）。
 *
 * 由测试调度器（[net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.TestBatchRunner]）
 * 基于验证链路（[net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentWorkflow]）发出的
 * [net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent] 驱动：
 * - onCaseScheduled：runOne 开始时（t0）。
 * - onProcessingStarted：收到 ProcessingStarted 事件（t1）。
 * - onLlmRequestStarted：收到 ModelCallStarted 事件（t2）。
 * - onLlmFirstConsumableOutput：收到首个非空 StreamingDelta（t3）。
 * - onLlmCompleted：收到 ModelCallCompleted 事件（t4）。
 * - onCaseFinalized：结果落定（t5），返回不可变 [TestCaseTiming]。
 */
interface TestCaseTimingCollector {

    fun onCaseScheduled(caseId: String)

    fun onProcessingStarted(caseId: String)

    fun onLlmRequestStarted(caseId: String, requestId: String, model: String? = null, providerType: String? = null)

    fun onLlmFirstConsumableOutput(caseId: String, requestId: String)

    fun onLlmCompleted(caseId: String, requestId: String, model: String? = null, providerType: String? = null)

    /** 落定 t5 并返回该用例的最终 [TestCaseTiming]。可多次安全调用（只落定一次）。 */
    fun onCaseFinalized(caseId: String): TestCaseTiming

    /** 请求级 Trace（诊断用；同 requestId 重试会各自保留）。 */
    fun requestTrace(caseId: String): List<LlmRequestTrace>

    /** 当前已落定的部分计时（页面实时展示用；未落定阶段保持空）。 */
    fun partialTiming(caseId: String): TestCaseTiming?
}

/**
 * 基于单调时钟的默认实现（IVI-IVAI-DSN-CR-014）。
 *
 * 时钟注入便于单元测试控制时间；生产使用 [System.nanoTime]。
 * 线程安全：按 caseId 隔离状态，支持并行批次不串线。
 */
class DefaultTestCaseTimingCollector(
    private val clock: () -> Long = { System.nanoTime() }
) : TestCaseTimingCollector {

    private class CaseState {
        var t0: Long? = null
        var t1: Long? = null
        var llmRequestStarted: Long? = null
        var llmFirstOutput: Long? = null
        var llmCompleted: Long? = null
        var llmInvoked = false
        var finalized = false
        var t5: Long? = null
        val requestTrace = LinkedHashMap<String, LlmRequestTrace>()
    }

    private val states = ConcurrentHashMap<String, CaseState>()

    override fun onCaseScheduled(caseId: String) {
        state(caseId).t0 = clock()
    }

    override fun onProcessingStarted(caseId: String) {
        state(caseId).t1 = clock()
    }

    override fun onLlmRequestStarted(caseId: String, requestId: String, model: String?, providerType: String?) {
        val s = state(caseId)
        s.llmInvoked = true
        // 首次请求发起作为用例级 t2 起点。
        if (s.llmRequestStarted == null) s.llmRequestStarted = clock()
        s.requestTrace.putIfAbsent(
            requestId,
            LlmRequestTrace(
                requestId = requestId,
                startedAtMs = nowMs(),
                model = model,
                providerType = providerType
            )
        )
    }

    override fun onLlmFirstConsumableOutput(caseId: String, requestId: String) {
        val s = state(caseId)
        s.llmInvoked = true
        // 首个可消费输出只写入一次（空心跳 / 纯元数据不计首字）。
        if (s.llmFirstOutput == null) s.llmFirstOutput = clock()
        s.requestTrace[requestId]?.let { trace ->
            s.requestTrace[requestId] = trace.copy(firstConsumableOutputAtMs = nowMs())
        }
    }

    override fun onLlmCompleted(caseId: String, requestId: String, model: String?, providerType: String?) {
        val s = state(caseId)
        s.llmInvoked = true
        // 最后一次必要响应完成作为用例级 t4 终点。
        s.llmCompleted = clock()
        // 非流式 LLM / Tool Call-only 无文本增量时：完整返回时刻同时落定首字（t3=t4）。
        if (s.llmFirstOutput == null) s.llmFirstOutput = s.llmCompleted
        s.requestTrace.putIfAbsent(
            requestId,
            LlmRequestTrace(
                requestId = requestId,
                startedAtMs = nowMs(),
                model = model,
                providerType = providerType
            )
        )
        val trace = s.requestTrace[requestId]
        if (trace != null) {
            s.requestTrace[requestId] = trace.copy(
                completedAtMs = nowMs(),
                firstConsumableOutputAtMs = trace.firstConsumableOutputAtMs ?: nowMs(),
                model = model ?: trace.model,
                providerType = providerType ?: trace.providerType
            )
        }
    }

    override fun onCaseFinalized(caseId: String): TestCaseTiming {
        val s = state(caseId)
        if (!s.finalized) {
            s.finalized = true
            s.t5 = clock()
        }
        return buildTiming(s, s.t5!!)
    }

    override fun requestTrace(caseId: String): List<LlmRequestTrace> =
        states[caseId]?.requestTrace?.values?.toList() ?: emptyList()

    override fun partialTiming(caseId: String): TestCaseTiming? {
        val s = states[caseId] ?: return null
        val t0 = s.t0 ?: return null
        val now = clock()
        val processing = s.t1?.let { msOf(it - t0) }
        return TestCaseTiming(
            processingStartLatencyMs = processing ?: -1L,
            llmFirstTokenLatencyMs = s.llmRequestStarted?.let { start -> s.llmFirstOutput?.let { msOf(it - start) } },
            llmCompleteLatencyMs = s.llmRequestStarted?.let { start -> s.llmCompleted?.let { msOf(it - start) } },
            totalCaseDurationMs = msOf(now - t0),
            llmInvoked = s.llmInvoked
        )
    }

    private fun buildTiming(s: CaseState, t5: Long): TestCaseTiming {
        val t0 = s.t0 ?: 0L
        val t1 = s.t1 ?: t0
        val llmStart = s.llmRequestStarted
        return TestCaseTiming(
            processingStartLatencyMs = msOf(t1 - t0),
            llmFirstTokenLatencyMs = llmStart?.let { start -> s.llmFirstOutput?.let { msOf(it - start) } },
            llmCompleteLatencyMs = llmStart?.let { start -> s.llmCompleted?.let { msOf(it - start) } },
            totalCaseDurationMs = msOf(t5 - t0),
            llmInvoked = s.llmInvoked
        )
    }

    private fun state(caseId: String): CaseState = states.getOrPut(caseId) { CaseState() }

    /** 请求级 Trace 的时间戳同样以毫秒输出（诊断用）。 */
    private fun nowMs(): Long = msOf(clock())

    /** 纳秒差值 → 毫秒（四舍五入，保证非负语义）。 */
    private fun msOf(ns: Long): Long = (ns + NANO_PER_MS / 2) / NANO_PER_MS

    private companion object {
        const val NANO_PER_MS = 1_000_000L
    }
}
