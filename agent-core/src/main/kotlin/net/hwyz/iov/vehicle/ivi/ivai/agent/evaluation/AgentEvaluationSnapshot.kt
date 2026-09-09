package net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainCandidate
import net.hwyz.iov.vehicle.ivi.ivai.agent.execution.TerminalStage
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId

/**
 * 测试目标类型（IVI-IVAI-DSN-CR-012）：Tool 与同名 Workflow 不得互相匹配。
 * 定义在 agent-core（而非 app-demo 测试资产模型）以保证依赖方向：Agent 执行的
 * 快照契约与测试用例模型共用同一个枚举。
 */
@Serializable
enum class TargetType {
    TOOL,
    WORKFLOW
}

/**
 * 测试执行的目标（IVI-IVAI-DSN-CR-012）。
 *
 * [type] + [id] 联合唯一标识一个可执行目标；Tool 与 Workflow 即使 ID 相同也
 * 视为不同目标（评分规则 4）。
 */
@Serializable
data class ActualTarget(
    val type: TargetType,
    val id: String
)

/**
 * 单条用例的终态（IVI-IVAI-DSN-CR-012 批次状态机）。由 Agent 结构化执行状态
 * 投影，不由自然语言回复推断。
 *
 *  - SUCCEEDED：Tool/Workflow 执行成功。
 *  - REPLIED：纯回复（对话、追问、云端预留等，未产生可执行目标）。
 *  - REJECTED：拒绝（安全/未授权/未知工具/无可用能力包）。
 *  - NEED_DIALOGUE：缺少必填参数，等待用户补充。
 *  - WAITING_CONFIRMATION：策略要求确认，等待用户确认/取消。
 *  - FAILED：模型/解析/Schema/执行失败。
 *  - TIMEOUT：批次侧单条超时（IVAI-TEST-005，取消活动请求后继续）。
 *  - CANCELLED：用户显式取消批次或取消待确认操作。
 */
@Serializable
enum class EvaluationTerminalStatus {
    SUCCEEDED,
    REPLIED,
    REJECTED,
    NEED_DIALOGUE,
    WAITING_CONFIRMATION,
    FAILED,
    TIMEOUT,
    CANCELLED
}

/**
 * 受控的 Agent 结构化执行结果投影（IVI-IVAI-DSN-CR-012）。
 *
 * 由 [EvaluationSnapshotProjector] 从既有结构化执行状态与 AgentEvent 投影，
 * 供测试采集器按 [requestId] 关联并完成五维评分。**不得包含** System Prompt、
 * 模型思维链、密钥、完整网络地址、内部堆栈或未经脱敏的模型原始响应。
 *
 * Snapshot 仅在 debug/test 能力开启时向测试客户端暴露；普通聊天消息模型不增加
 * 这些内部字段。
 */
@Serializable
data class AgentEvaluationSnapshot(
    val requestId: String,
    val sessionId: String,
    val initialTier: IntentTier?,
    val finalTier: IntentTier?,
    val initialDomains: List<DomainCandidate> = emptyList(),
    val finalDomain: BusinessDomainId? = null,
    val selectedCapabilityPackIds: Set<String> = emptySet(),
    val selectedTarget: ActualTarget? = null,
    val normalizedArguments: JsonObject? = null,
    val terminalStatus: EvaluationTerminalStatus,
    val reasonCode: String? = null,
    val governanceVersion: String? = null,
    val candidateVersion: String? = null,
    // ---- CR-016 诊断辅助字段（可选扩展，不改变 16 个必选列语义） ----
    /** 终止阶段（非成功终态必填）。 */
    val terminalStage: TerminalStage? = null,
    /** 失败原因（细分错误码 IVAI-STATE-001 等，供定位失败层）。 */
    val failureReason: String? = null,
    /** 受控候选集 Hash（可观测性）。 */
    val candidateSetHash: String? = null,
    /** 命中的确定性规则 ID（L0/L1 候选来源）。 */
    val matchedRuleIds: List<String> = emptyList(),
    /** 参数 → 来源（USER_EXPLICIT / ALIAS_MAPPING / RULE_PRESET / ...）。 */
    val argumentSources: Map<String, String> = emptyMap(),
    /** CR-016: 是否发起过模型请求（L1 状态一致性证据）。 */
    val llmInvoked: Boolean? = null,
    /** CR-016: 模型请求计数。 */
    val modelRequestCount: Int? = null,
    // ---- CR-017 检索与模型分发可观测性（REQ-183） ----
    /** 是否执行过 L1 检索（RAG 命中或回退）。 */
    val retrievalInvoked: Boolean? = null,
    /** L1 检索后、包过滤收敛后的候选数。 */
    val retrievedCandidateCount: Int? = null,
    /** 是否发起过模型分发（与 llmInvoked 一致）。 */
    val modelDispatchAttempted: Boolean? = null,
    // ---- CR-018 检索 Top-K 可观测性（诊断导出：RAG Top-K / 选中候选及分数） ----
    /** L1 候选 Top-K canonical Tool ID（RAG 召回或固定候选）。 */
    val retrievedCandidateIds: List<String> = emptyList(),
    /** L1 候选 Top-K 最终分数（与 retrievedCandidateIds 对齐）。 */
    val retrievedCandidateScores: List<Double> = emptyList()
)
