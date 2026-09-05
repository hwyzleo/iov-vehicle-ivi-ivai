package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import kotlinx.serialization.Serializable
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentExecutionPath
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.TurnDebugInfo
import net.hwyz.iov.vehicle.ivi.ivai.model.AgentPerformanceMetrics
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentInputSource

/**
 * One chat message in the conversation stream (IVI-IVAI-DSN-CR-002).
 * Traceable via messageId/sessionId/turnId/requestId.
 */
@Serializable
data class ChatMessage(
    val messageId: String,
    val sessionId: String,
    val turnId: String,
    val requestId: String?,
    val role: ChatRole,
    val type: ChatMessageType,
    val text: String,
    val timestamp: Long,
    val status: ChatMessageStatus = ChatMessageStatus.FINAL,
    val confirmation: ConfirmationUiModel? = null,
    val retryable: Boolean = false,
    /** When set, this message is a retry of the request identified here. */
    val retryOfRequestId: String? = null,
    /** Per-turn debug details shown in the collapsible detail panel. */
    val details: TurnDebugInfo? = null,
    /** Segmented performance of the final turn (CR-004), shown under the reply bubble. */
    val performance: AgentPerformanceMetrics? = null,
    /** Whether the performance detail panel is expanded (default collapsed). */
    val isPerformanceExpanded: Boolean = false,
    /** CR-005: final tier badge label (L0·本地直达 … 未执行·已拒绝), bound to the bubble. */
    val executionTierLabel: String? = null,
    /** CR-005: structured execution path for the collapsible detail panel. */
    val executionPath: AgentExecutionPath? = null,
    /** CR-006: input provenance (VOICE_ASR) kept for diagnostics only. */
    val inputSource: AgentInputSource? = null
)

/**
 * Data backing a confirmation card: the user acts on [confirmationId].
 */
@Serializable
data class ConfirmationUiModel(
    val confirmationId: String,
    val toolId: String,
    val toolName: String,
    val text: String
)

@Serializable
enum class ChatRole { USER, AGENT }

@Serializable
enum class ChatMessageType { TEXT, PROCESSING, CONFIRMATION, TOOL_RESULT, ERROR }

/**
 * Design baseline statuses plus TIMEOUT so success / failure / timeout are all
 * visibly distinguishable without relying on color alone.
 */
@Serializable
enum class ChatMessageStatus {
    SENDING,
    PROCESSING,
    WAITING_USER,
    FINAL,
    FAILED,
    TIMEOUT,
    CANCELLED
}
