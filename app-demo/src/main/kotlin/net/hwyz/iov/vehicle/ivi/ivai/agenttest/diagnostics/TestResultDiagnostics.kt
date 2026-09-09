package net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics

import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.execution.TerminalStage

/**
 * 测试结果诊断投影（IVI-IVAI-DSN-CR-016）。
 *
 * 从结构化 [AgentEvaluationSnapshot] 提取故障定位辅助字段（可选扩展，不改变
 * CR-014 的 16 个 XLSX 必选列语义）：
 *  - terminalStage / failureReason：定位失败发生在哪一层（Router / Retriever /
 *    Model / Parse / Schema / Policy / Execution）；
 *  - candidateSetHash / matchedRuleIds / argumentSources：候选来源与参数来源；
 *  - llmInvoked / modelRequestCount：L1 状态一致性证据。
 *
 * 所有非成功终态（REJECTED / NEED_DIALOGUE / FAILED / TIMEOUT / CANCELLED /
 * WAITING_CONFIRMATION）必须具有 terminalStage 与非空 reasonCode
 * （IVAI-STATE-001 校验由 agent-core 的 ExecutionInvariantValidator 完成）。
 */
data class CaseDiagnostics(
    val terminalStage: TerminalStage? = null,
    val failureReason: String? = null,
    val candidateSetHash: String? = null,
    val matchedRuleIds: List<String> = emptyList(),
    val argumentSources: Map<String, String> = emptyMap(),
    val llmInvoked: Boolean = false,
    val modelRequestCount: Int = 0,
    val invariantStateCode: String? = null
) {
    /** 是否存在可诊断的故障定位信息。 */
    val hasDiagnostics: Boolean
        get() = terminalStage != null || failureReason != null || invariantStateCode != null
}

/** CR-016 测试诊断投影器。 */
object TestResultDiagnostics {

    /** 从快照投影诊断字段。 */
    fun project(snapshot: AgentEvaluationSnapshot?): CaseDiagnostics {
        if (snapshot == null) return CaseDiagnostics()
        // IVAI-STATE-001：不变量违规时 failureReason 携带校验消息，reasonCode 为细分码。
        val invariant = snapshot.reasonCode
            ?.takeIf { it == INVARIANT_CODE }
        return CaseDiagnostics(
            terminalStage = snapshot.terminalStage,
            failureReason = snapshot.failureReason,
            candidateSetHash = snapshot.candidateSetHash,
            matchedRuleIds = snapshot.matchedRuleIds,
            argumentSources = snapshot.argumentSources,
            llmInvoked = snapshot.llmInvoked ?: false,
            modelRequestCount = snapshot.modelRequestCount ?: 0,
            invariantStateCode = invariant
        )
    }

    /** 非成功终态必须具有 terminalStage 与 reasonCode（诊断完整性检查）。 */
    fun isDiagnosable(snapshot: AgentEvaluationSnapshot?): Boolean {
        if (snapshot == null) return false
        if (snapshot.terminalStatus == EvaluationTerminalStatus.SUCCEEDED ||
            snapshot.terminalStatus == EvaluationTerminalStatus.REPLIED
        ) {
            return true
        }
        return snapshot.terminalStage != null && !snapshot.reasonCode.isNullOrBlank()
    }

    private const val INVARIANT_CODE = "IVAI-STATE-001"
}
