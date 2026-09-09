package net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics

import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.execution.TerminalStage

/**
 * 测试结果诊断投影（IVI-IVAI-DSN-CR-016 + CR-018）。
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
 *
 * CR-018：拆分运行时失败与评分差异（IVAI-SCORE-DIAG-001 兜底“无法区分”），
 * 并导出 Domain 证据 / RAG Top-K / 选中候选及分数。
 */
data class CaseDiagnostics(
    val terminalStage: TerminalStage? = null,
    val failureReason: String? = null,
    val candidateSetHash: String? = null,
    val matchedRuleIds: List<String> = emptyList(),
    val argumentSources: Map<String, String> = emptyMap(),
    val llmInvoked: Boolean = false,
    val modelRequestCount: Int = 0,
    val invariantStateCode: String? = null,
    // ---- CR-018 诊断导出辅助字段 ----
    val initialDomains: List<String> = emptyList(),
    val finalDomain: String? = null,
    val retrievedCandidateIds: List<String> = emptyList(),
    val retrievedCandidateScores: List<Double> = emptyList(),
    val selectedTargetId: String? = null
) {
    /** 是否存在可诊断的故障定位信息。 */
    val hasDiagnostics: Boolean
        get() = terminalStage != null || failureReason != null || invariantStateCode != null
}

/** CR-018：运行时终态（快照缺失时 UNKNOWN）。 */
enum class RuntimeStatus {
    SUCCEEDED,
    REPLIED,
    REJECTED,
    NEED_DIALOGUE,
    WAITING_CONFIRMATION,
    FAILED,
    TIMEOUT,
    CANCELLED,
    UNKNOWN
}

/** CR-018：评分状态（未评分 / 通过 / 失败）。 */
enum class ScoreStatus {
    PASSED,
    FAILED,
    NOT_SCORED
}

/** CR-018：五维评分失败维度。 */
enum class ScoreDimension {
    TIER,
    DOMAIN,
    CAPABILITY_PACK,
    TARGET,
    ARGUMENTS
}

/**
 * CR-018：运行时失败与评分差异拆分。
 *
 *  - runtimeStatus / runtimeTerminalStage / runtimeReasonCode：运行时非成功终态定位；
 *  - scoreStatus / mismatchDimensions / mismatchDetail：评分不匹配维度；
 *  - 运行时成功但测试预期不匹配只记为 score mismatch，不得伪装为运行时失败；
 *    无法区分时置 [isScoreMismatchOnly]=false 且 reasonCode=IVAI-SCORE-DIAG-001。
 */
data class TestFailureDiagnostics(
    val runtimeStatus: RuntimeStatus,
    val runtimeTerminalStage: TerminalStage? = null,
    val runtimeReasonCode: String? = null,
    val scoreStatus: ScoreStatus = ScoreStatus.NOT_SCORED,
    val mismatchDimensions: Set<ScoreDimension> = emptySet(),
    val mismatchDetail: String? = null,
    // ---- CR-018 导出辅助字段 ----
    val domainEvidence: String? = null,
    val ragTopK: String? = null,
    val selectedCandidate: String? = null
) {
    /** 运行时成功但评分失败 → 仅评分差异（不视为运行时失败）。 */
    val isScoreMismatchOnly: Boolean
        get() = runtimeStatus in SCORE_ONLY_RUNTIME_STATUSES && scoreStatus == ScoreStatus.FAILED

    /** 无法区分运行时失败与评分差异（IVAI-SCORE-DIAG-001）。 */
    val indistinguishable: Boolean
        get() = runtimeStatus == RuntimeStatus.UNKNOWN && scoreStatus == ScoreStatus.FAILED

    companion object {
        private val SCORE_ONLY_RUNTIME_STATUSES = setOf(
            RuntimeStatus.SUCCEEDED,
            RuntimeStatus.REPLIED
        )

        const val SCORE_DIAG_CODE = "IVAI-SCORE-DIAG-001"
    }
}

/** CR-016 + CR-018 测试诊断投影器。 */
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
            invariantStateCode = invariant,
            initialDomains = snapshot.initialDomains.map { "${it.domainId.name}" },
            finalDomain = snapshot.finalDomain?.name,
            retrievedCandidateIds = snapshot.retrievedCandidateIds,
            retrievedCandidateScores = snapshot.retrievedCandidateScores,
            selectedTargetId = snapshot.selectedTarget?.id
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

    /** CR-018：终态 → RuntimeStatus。 */
    fun runtimeStatusOf(status: EvaluationTerminalStatus?): RuntimeStatus = when (status) {
        EvaluationTerminalStatus.SUCCEEDED -> RuntimeStatus.SUCCEEDED
        EvaluationTerminalStatus.REPLIED -> RuntimeStatus.REPLIED
        EvaluationTerminalStatus.REJECTED -> RuntimeStatus.REJECTED
        EvaluationTerminalStatus.NEED_DIALOGUE -> RuntimeStatus.NEED_DIALOGUE
        EvaluationTerminalStatus.WAITING_CONFIRMATION -> RuntimeStatus.WAITING_CONFIRMATION
        EvaluationTerminalStatus.FAILED -> RuntimeStatus.FAILED
        EvaluationTerminalStatus.TIMEOUT -> RuntimeStatus.TIMEOUT
        EvaluationTerminalStatus.CANCELLED -> RuntimeStatus.CANCELLED
        null -> RuntimeStatus.UNKNOWN
    }

    private const val INVARIANT_CODE = "IVAI-STATE-001"
}
