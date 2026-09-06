package net.hwyz.iov.vehicle.ivi.ivai.agent.event

import kotlinx.serialization.Serializable
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagExecutionInfo
import net.hwyz.iov.vehicle.ivi.ivai.model.AgentPerformanceMetrics

/**
 * CR-008 领域路由 / 能力包 / Workflow 可观测信息（IVI-IVAI-DSN-CR-008 可观测性）。
 * 记录初始/最终领域、操作类型、能力包版本、候选数量过滤与 Workflow 执行轨迹。
 * 不记录未经许可的完整语音文本、敏感上下文或密钥。
 */
@Serializable
data class Cr008DebugInfo(
    val domain: String? = null,
    val operationType: String? = null,
    val domainReasonCode: String? = null,
    val selectedPacks: List<String> = emptyList(),
    val packVersion: String? = null,
    val preFilterCandidateCount: Int? = null,
    val postFilterCandidateCount: Int? = null,
    val workflowId: String? = null,
    val workflowState: String? = null,
    val workflowStepCount: Int? = null,
    val workflowStepResults: List<String> = emptyList(),
    val compensationResult: String? = null,
    val workflowErrorCode: String? = null
)

/**
 * A single intent proposed by the model (before validation / execution).
 */
@Serializable
data class ParsedIntentSummary(
    val toolId: String,
    val functionId: String? = null,
    val arguments: Map<String, String> = emptyMap()
)

/**
 * Summary of the parsed top-level model output.
 */
@Serializable
data class ParsedOutputSummary(
    val route: String,
    val confidence: Double,
    val riskLevel: String,
    val needConfirmation: Boolean,
    val missingArguments: List<String>,
    val reasonCode: String?,
    val intents: List<ParsedIntentSummary> = emptyList()
)

/**
 * Tool execution detail (triggered tool, status, latency).
 */
@Serializable
data class ToolDebugInfo(
    val toolId: String? = null,
    val status: String? = null,
    val message: String? = null,
    val errorCode: String? = null,
    val latencyMs: Long? = null
)

/**
 * Per-turn debug details shown in the chat detail panel (IVI-IVAI-DSN-CR-002,
 * CR-004): segmented performance, parsed result, triggered tool and error info.
 *
 * Privacy boundary (CR-004): the System Prompt and the composed message
 * orchestration are deliberately NOT part of this payload — Agent Events never
 * expose the full prompt to chat consumers; the read-only PromptInfo settings
 * page serves the prompt snapshot instead. [errorDetail] is a sanitized,
 * truncated diagnostic reason (e.g. model-output-parse failure) shown only in
 * the debug panel — never raw secrets or full model output.
 */
@Serializable
data class TurnDebugInfo(
    val turnId: String,
    val requestId: String,
    val performance: AgentPerformanceMetrics? = null,
    val parsed: ParsedOutputSummary? = null,
    val tool: ToolDebugInfo? = null,
    val state: String? = null,
    val route: String? = null,
    val errorCode: String? = null,
    val errorDetail: String? = null,
    val replayed: Boolean = false,
    // CR-005: tier routing observability
    val intentTier: String? = null,
    val finalTier: String? = null,
    val transitions: List<TierTransition> = emptyList(),
    val candidateSource: String? = null,
    val rag: RagExecutionInfo? = null,
    // CR-008: 领域路由 / 能力包 / Workflow 可观测性
    val cr008: Cr008DebugInfo? = null
)
