package net.hwyz.iov.vehicle.ivi.ivai.agenttest.result

import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus

/**
 * 按 requestId 汇聚 Agent 结构化终态（IVI-IVAI-DSN-CR-012）。
 *
 * 只消费结构化事件类型（终态事件），**不解析自然语言回复**；结构化实际字段以
 * 快照契约为权威来源（UI 不得根据回复文案反向推断）。
 *
 * 职责：
 *  - 识别「终态事件」（Reply / ToolExecutionFinished / TurnFailed / TurnCancelled /
 *    ConfirmationRequired）；
 *  - 按 requestId 去重：忽略其他请求的事件，重复终态事件只生效一次；
 *  - 提供快照缺失时的兜底终态（事件类型推导），供错误码 IVAI-TEST-006 诊断。
 */
class ActualResultCollector(private val activeRequestId: String?) {

    var terminalStatus: EvaluationTerminalStatus? = null
        private set

    var errorCode: String? = null
        private set

    private var seenTerminal = false

    /** 返回 true 表示收到活动 requestId 的终态事件（可结束等待）。 */
    fun onEvent(event: AgentEvent): Boolean {
        if (activeRequestId != null && event.requestId != activeRequestId) return false
        if (seenTerminal) return true
        if (!isTerminalEvent(event)) return false
        seenTerminal = true
        terminalStatus = terminalStatusOf(event)
        errorCode = (event as? AgentEvent.TurnFailed)?.errorCode
            ?: (event as? AgentEvent.ToolExecutionFinished)?.errorCode
        return true
    }

    fun isTerminalEvent(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.Reply,
        is AgentEvent.ToolExecutionFinished,
        is AgentEvent.TurnFailed,
        is AgentEvent.TurnCancelled,
        is AgentEvent.ConfirmationRequired -> true
        else -> false
    }

    /** 兜底终态（快照缺失时使用；权威来源仍是快照）。 */
    fun terminalStatusOf(event: AgentEvent): EvaluationTerminalStatus = when (event) {
        is AgentEvent.Reply -> EvaluationTerminalStatus.REPLIED
        is AgentEvent.ToolExecutionFinished -> when (event.status) {
            ExecutionStatus.SUCCEEDED -> EvaluationTerminalStatus.SUCCEEDED
            ExecutionStatus.TIMEOUT -> EvaluationTerminalStatus.TIMEOUT
            ExecutionStatus.FAILED -> EvaluationTerminalStatus.FAILED
        }
        is AgentEvent.TurnFailed -> EvaluationTerminalStatus.FAILED
        is AgentEvent.TurnCancelled -> EvaluationTerminalStatus.CANCELLED
        is AgentEvent.ConfirmationRequired -> EvaluationTerminalStatus.WAITING_CONFIRMATION
        else -> EvaluationTerminalStatus.FAILED
    }
}
