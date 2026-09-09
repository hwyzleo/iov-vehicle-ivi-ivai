package net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation

import kotlinx.serialization.json.JsonObject
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainCandidate
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentExecutionPath
import net.hwyz.iov.vehicle.ivi.ivai.agent.execution.TerminalStage
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentRoute
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus

/**
 * 快照投影输入（IVI-IVAI-DSN-CR-012）：既有结构化执行状态 + AgentEvent 中可
 * 投影的事实集合。AgentWorkflow 在终态时把内部执行状态填充后调用
 * [EvaluationSnapshotProjector.project]。
 */
data class SnapshotFacts(
    val requestId: String,
    val sessionId: String,
    val executionPath: AgentExecutionPath? = null,
    val initialDomains: List<DomainCandidate> = emptyList(),
    val finalDomain: BusinessDomainId? = null,
    val selectedCapabilityPackIds: Set<String> = emptySet(),
    val selectedTarget: ActualTarget? = null,
    val normalizedArguments: JsonObject? = null,
    val state: AgentState? = null,
    val route: AgentRoute? = null,
    val executionStatus: ExecutionStatus? = null,
    val pendingConfirmation: Boolean = false,
    val reasonCode: String? = null,
    val governanceVersion: String? = null,
    val candidateVersion: String? = null,
    val cancelled: Boolean = false,
    // ---- CR-016 诊断辅助字段 ----
    val terminalStage: TerminalStage? = null,
    val failureReason: String? = null,
    val candidateSetHash: String? = null,
    val matchedRuleIds: List<String> = emptyList(),
    val argumentSources: Map<String, String> = emptyMap(),
    val llmInvoked: Boolean? = null,
    val modelRequestCount: Int? = null
)

/**
 * 从既有结构化执行状态投影 [AgentEvaluationSnapshot]（IVI-IVAI-DSN-CR-012）。
 *
 * 字段来源：
 *  - actualTier ← [AgentExecutionPath.finalTier]
 *  - actualDomain ← 完成 Domain Router 后的最终业务领域
 *  - actualCapabilityPacks ← CapabilitySnapshot.selectedPackIds
 *  - actualTarget ← 完成 Candidate Boundary 后的 canonical Tool/Workflow ID
 *  - actualArguments ← Schema 默认值、Alias 预置值和单位规范化后的 canonical 参数
 *
 * 目标与参数应在实际执行前的最终合法候选阶段冻结；即使 Mock Adapter 执行失败，
 * 仍可显示已确定的目标和参数，执行失败状态单独记录，不改变前述匹配事实。
 *
 * 本投影器不接触 System Prompt / 思维链 / 密钥 / 模型原始响应，输出即脱敏契约。
 */
object EvaluationSnapshotProjector {

    fun project(facts: SnapshotFacts): AgentEvaluationSnapshot {
        val path = facts.executionPath
        return AgentEvaluationSnapshot(
            requestId = facts.requestId,
            sessionId = facts.sessionId,
            initialTier = path?.initialTier,
            finalTier = path?.finalTier,
            initialDomains = facts.initialDomains,
            finalDomain = facts.finalDomain,
            selectedCapabilityPackIds = facts.selectedCapabilityPackIds,
            selectedTarget = facts.selectedTarget,
            normalizedArguments = facts.normalizedArguments,
            terminalStatus = terminalStatus(facts),
            reasonCode = facts.reasonCode ?: path?.finalReasonCode,
            governanceVersion = facts.governanceVersion,
            candidateVersion = facts.candidateVersion,
            terminalStage = facts.terminalStage,
            failureReason = facts.failureReason,
            candidateSetHash = facts.candidateSetHash,
            matchedRuleIds = facts.matchedRuleIds,
            argumentSources = facts.argumentSources,
            llmInvoked = facts.llmInvoked,
            modelRequestCount = facts.modelRequestCount
        )
    }

    /**
     * 终态映射（与批次状态机一致）：
     *  - 显式取消 → CANCELLED
     *  - 执行超时 → TIMEOUT
     *  - 执行成功 → SUCCEEDED
     *  - 等待确认 → WAITING_CONFIRMATION
     *  - 等待用户补充参数 / 对话中 → NEED_DIALOGUE
     *  - 拒绝 → REJECTED
     *  - 其余失败态 → FAILED
     *  - 纯回复路径（对话 / 云端预留）→ REPLIED
     */
    fun terminalStatus(facts: SnapshotFacts): EvaluationTerminalStatus = when {
        facts.cancelled -> EvaluationTerminalStatus.CANCELLED
        facts.executionStatus == ExecutionStatus.TIMEOUT -> EvaluationTerminalStatus.TIMEOUT
        facts.executionStatus == ExecutionStatus.SUCCEEDED ||
            facts.state == AgentState.SUCCEEDED -> EvaluationTerminalStatus.SUCCEEDED
        facts.executionStatus == ExecutionStatus.FAILED ||
            facts.state == AgentState.FAILED || facts.state == AgentState.TIMEOUT ->
            EvaluationTerminalStatus.FAILED
        facts.pendingConfirmation &&
            (facts.state == AgentState.WAITING_USER || facts.state == AgentState.NEED_DIALOGUE) ->
            EvaluationTerminalStatus.WAITING_CONFIRMATION
        facts.state == AgentState.WAITING_USER ||
            facts.state == AgentState.NEED_DIALOGUE -> EvaluationTerminalStatus.NEED_DIALOGUE
        facts.state == AgentState.REJECTED -> EvaluationTerminalStatus.REJECTED
        facts.route == AgentRoute.LOCAL_DIALOGUE || facts.route == AgentRoute.CLOUD_AI ->
            EvaluationTerminalStatus.REPLIED
        else -> EvaluationTerminalStatus.FAILED
    }

    /** 由终态推导测试用例执行状态（供 UI / Runner 汇总使用）。 */
    fun isIncomplete(status: EvaluationTerminalStatus): Boolean =
        status == EvaluationTerminalStatus.NEED_DIALOGUE ||
            status == EvaluationTerminalStatus.WAITING_CONFIRMATION

    /** 终态中不产生可执行目标的状态（五维评分时目标/参数维度按 null 处理）。 */
    fun hasExecutableTarget(status: EvaluationTerminalStatus): Boolean =
        status == EvaluationTerminalStatus.SUCCEEDED ||
            status == EvaluationTerminalStatus.TIMEOUT ||
            status == EvaluationTerminalStatus.FAILED
}
