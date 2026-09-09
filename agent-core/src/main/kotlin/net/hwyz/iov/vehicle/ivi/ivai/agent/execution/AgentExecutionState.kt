package net.hwyz.iov.vehicle.ivi.ivai.agent.execution

import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier

/**
 * 请求级执行状态跟踪器（IVI-IVAI-DSN-CR-016）。
 *
 * 记录一轮执行的状态事件、模型请求计数、是否调用 LLM、最终层级、终止阶段与
 * 终态；终态封口时组装 [ExecutionInvariantContext] 交给
 * [ExecutionInvariantValidator] 校验。AgentWorkflow 在每个 Turn 创建实例，
 * 在状态转换 / 模型请求 / 终态时更新。
 */
class AgentExecutionState(
    val requestId: String,
    var finalTier: IntentTier? = null
) {
    /** 已记录的状态事件（顺序）。 */
    private val stateEvents = mutableListOf<AgentState>()

    /** 是否发起了至少一次模型请求。 */
    var llmInvoked: Boolean = false
        private set

    /** 模型请求计数（MODEL_REQUEST_STARTED 次数）。 */
    var modelRequestCount: Int = 0
        private set

    /** 最终终止阶段（非成功终态必填）。 */
    var terminalStage: TerminalStage? = null
        private set

    /** 终止原因码（非成功终态必填）。 */
    var reasonCode: String? = null
        private set

    /** 终态。 */
    var terminalStatus: EvaluationTerminalStatus? = null
        private set

    val recordedStates: List<AgentState> get() = stateEvents.toList()

    /** 记录一个状态事件。 */
    fun record(state: AgentState) {
        stateEvents += state
    }

    /** 记录一次模型请求开始。 */
    fun recordModelRequest() {
        llmInvoked = true
        modelRequestCount += 1
    }

    /** 记录终止阶段 / 原因 / 终态。 */
    fun markTerminal(
        terminalStage: TerminalStage?,
        reasonCode: String?,
        terminalStatus: EvaluationTerminalStatus
    ) {
        this.terminalStage = terminalStage
        this.reasonCode = reasonCode
        this.terminalStatus = terminalStatus
    }

    /** 组装不变量校验输入。 */
    fun invariantContext(
        selectedTarget: net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.ActualTarget?
    ): ExecutionInvariantContext = ExecutionInvariantContext(
        finalTier = finalTier,
        llmInvoked = llmInvoked,
        modelRequestCount = modelRequestCount,
        terminalStatus = terminalStatus ?: EvaluationTerminalStatus.FAILED,
        terminalStage = terminalStage,
        reasonCode = reasonCode,
        selectedTarget = selectedTarget
    )
}
