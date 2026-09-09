package net.hwyz.iov.vehicle.ivi.ivai.agent.execution

import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.ActualTarget
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier

/**
 * 执行不变量校验输入（IVI-IVAI-DSN-CR-016）。
 *
 * 由执行状态机在终态封口时组装，校验「最终层级 / LLM 是否调用 / 模型请求计数 /
 * 终态 / 终止阶段 / reasonCode / 目标」组合的一致性。禁止「L1 + llmInvoked=false +
 * 无目标 + 无终止原因」等非法组合进入 EvaluationSnapshot。
 */
data class ExecutionInvariantContext(
    val finalTier: IntentTier?,
    val llmInvoked: Boolean,
    val modelRequestCount: Int,
    val terminalStatus: EvaluationTerminalStatus,
    val terminalStage: TerminalStage?,
    val reasonCode: String?,
    val selectedTarget: ActualTarget?
)

/** 不变量校验结果。 */
sealed interface InvariantResult {
    /** 通过：组合一致，可封口快照。 */
    data object Pass : InvariantResult

    /** 违规：携带细分错误码（IVAI-STATE-001）与可诊断消息。 */
    data class Violated(
        val errorCode: String,
        val message: String
    ) : InvariantResult
}

/**
 * 执行状态不变量校验（IVI-IVAI-DSN-CR-016）。
 *
 * 必须满足：
 *  1. 需要模型消歧或抽参的 L1，必须至少存在一次模型请求（MODEL_REQUEST_STARTED）；
 *  2. llmInvoked=true 与模型请求计数必须一致；
 *  3. 未调用模型的 L1 只能是明确的候选为空、Retriever 不可用、需要追问、拒绝或
 *     状态失败分支；
 *  4. 非成功终态必须具有 terminalStage 与 reasonCode；
 *  5. 不满足不变量时输出 IVAI-STATE-001，不得生成看似正常的空 L1 结果。
 */
interface ExecutionInvariantValidator {
    fun validate(context: ExecutionInvariantContext): InvariantResult
}

/** 默认实现：面向 REQ-154/155/156 的确定性规则。 */
class DefaultExecutionInvariantValidator : ExecutionInvariantValidator {

    override fun validate(context: ExecutionInvariantContext): InvariantResult {
        if (context.finalTier == IntentTier.L1_LOCAL_TOOL_REASONING) {
            validateL1(context)?.let { return it }
        }
        validateTerminalStatus(context)?.let { return it }
        validateLlmConsistency(context)?.let { return it }
        return InvariantResult.Pass
    }

    /**
     * 规则 1/3：需要模型消歧或抽参的 L1 必须实际发起模型请求。
     *
     * 未调用模型的 L1 仅允许：
     *  - 候选为空 / Retriever 不可用（进入 NEED_DIALOGUE / REJECTED / FAILED）；
     *  - 需要追问（NEED_DIALOGUE，如 L0 缺参追问）；
     *  - 拒绝（REJECTED，如候选集外 Tool 被阻止、安全拒绝）；
     *  - 明确的失败分支（FAILED / CANCELLED / TIMEOUT 且带 reasonCode）。
     *
     * 成功终态（SUCCEEDED / REPLIED 等产生可执行目标的终态）在 L1 下必须发起模型。
     */
    private fun validateL1(context: ExecutionInvariantContext): InvariantResult? {
        if (context.llmInvoked) return null
        // 成功终态（含回复/执行）在 L1 下必须发起模型。
        if (context.terminalStatus == EvaluationTerminalStatus.SUCCEEDED ||
            context.terminalStatus == EvaluationTerminalStatus.REPLIED ||
            context.terminalStatus == EvaluationTerminalStatus.WAITING_CONFIRMATION
        ) {
            return InvariantResult.Violated(
                errorCode = STATE_INVARIANT_CODE,
                message = "L1 终态 ${context.terminalStatus} 需要模型消歧或抽参，但未发起 ModelRequest（llmInvoked=false）"
            )
        }
        // 未调用模型的其他终态必须给出明确终止阶段与原因。
        if (context.terminalStage == null || context.reasonCode.isNullOrBlank()) {
            return InvariantResult.Violated(
                errorCode = STATE_INVARIANT_CODE,
                message = "L1 未调用模型，但 terminalStage=${
                    context.terminalStage
                } / reasonCode=${context.reasonCode} 缺失，无法区分合法追问/拒绝/失败分支"
            )
        }
        return null
    }

    /**
     * 规则 4：非成功终态必须具有 terminalStage 与 reasonCode。
     * 成功（SUCCEEDED）与纯回复（REPLIED）可缺省终止阶段。
     */
    private fun validateTerminalStatus(context: ExecutionInvariantContext): InvariantResult? {
        if (context.terminalStatus == EvaluationTerminalStatus.SUCCEEDED ||
            context.terminalStatus == EvaluationTerminalStatus.REPLIED
        ) {
            return null
        }
        if (context.terminalStage == null || context.reasonCode.isNullOrBlank()) {
            return InvariantResult.Violated(
                errorCode = STATE_INVARIANT_CODE,
                message = "非成功终态 ${context.terminalStatus} 必须具有 terminalStage 与 reasonCode，" +
                    "当前 terminalStage=${context.terminalStage} / reasonCode=${context.reasonCode}"
            )
        }
        return null
    }

    /** 规则 2：llmInvoked=true 必须存在模型请求事件/计数。 */
    private fun validateLlmConsistency(context: ExecutionInvariantContext): InvariantResult? {
        if (context.llmInvoked && context.modelRequestCount <= 0) {
            return InvariantResult.Violated(
                errorCode = STATE_INVARIANT_CODE,
                message = "llmInvoked=true 但模型请求计数为 ${context.modelRequestCount}，状态事件与计时不一致"
            )
        }
        return null
    }

    private companion object {
        const val STATE_INVARIANT_CODE = "IVAI-STATE-001"
    }
}
