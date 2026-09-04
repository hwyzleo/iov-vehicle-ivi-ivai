package net.hwyz.iov.vehicle.ivi.ivai.agent.event

import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus

/**
 * Stable, user-facing events emitted by the agent workflow for the chat UI
 * (IVI-IVAI-DSN-CR-002). They expose only interaction state — never the system
 * prompt, model chain-of-thought, raw JSON, stack traces or internal addresses.
 */
sealed interface AgentEvent {
    val sessionId: String
    val turnId: String
    val requestId: String

    /** User text was accepted for a new turn (right-side user bubble). */
    data class UserSubmitted(
        override val sessionId: String,
        override val turnId: String,
        override val requestId: String,
        val text: String
    ) : AgentEvent

    /** Agent started working on the turn (left-side processing bubble). */
    data class ProcessingStarted(
        override val sessionId: String,
        override val turnId: String,
        override val requestId: String,
        val text: String = "正在处理…"
    ) : AgentEvent

    /**
     * Incremental raw model output while streaming (streaming enablement).
     * [text] is the cumulative content so far; the UI renders it into the
     * processing bubble and replaces it with the final user-facing result once
     * the turn completes. Never carries the System Prompt.
     */
    data class StreamingDelta(
        override val sessionId: String,
        override val turnId: String,
        override val requestId: String,
        val text: String
    ) : AgentEvent

    /** A plain agent reply, a follow-up question (追问) or a rejected response. */
    data class Reply(
        override val sessionId: String,
        override val turnId: String,
        override val requestId: String,
        val text: String
    ) : AgentEvent

    /** A tool needs user confirmation before it may run (left-side confirmation card). */
    data class ConfirmationRequired(
        override val sessionId: String,
        override val turnId: String,
        override val requestId: String,
        val confirmationId: String,
        val toolId: String,
        val toolName: String,
        val text: String
    ) : AgentEvent

    /** Tool execution started; the UI may update the processing text. */
    data class ToolExecutionStarted(
        override val sessionId: String,
        override val turnId: String,
        override val requestId: String,
        val toolId: String,
        val text: String
    ) : AgentEvent

    /** Tool execution finished (success / failure / timeout). */
    data class ToolExecutionFinished(
        override val sessionId: String,
        override val turnId: String,
        override val requestId: String,
        val toolId: String,
        val status: ExecutionStatus,
        val message: String,
        val errorCode: String? = null,
        val retryable: Boolean = false
    ) : AgentEvent

    /** The whole turn failed (model, parse, schema or validation). */
    data class TurnFailed(
        override val sessionId: String,
        override val turnId: String,
        override val requestId: String,
        val message: String,
        val errorCode: String? = null,
        val retryable: Boolean = false
    ) : AgentEvent

    /** A pending confirmation (or in-flight task) was cancelled by the user. */
    data class TurnCancelled(
        override val sessionId: String,
        override val turnId: String,
        override val requestId: String,
        val text: String
    ) : AgentEvent

    /**
     * Per-turn debug details (prompt orchestration, raw model output, parsed
     * result, triggered tool and latency stats). Stable interaction events
     * remain lean; this carries the debugging payload for the chat detail panel.
     */
    data class DebugInfo(
        override val sessionId: String,
        override val turnId: String,
        override val requestId: String,
        val debug: TurnDebugInfo
    ) : AgentEvent
}

/**
 * Receives [AgentEvent]s produced by the workflow. Implementations must be fast
 * and non-blocking (the UI maps events into its own StateFlow).
 */
fun interface AgentEventListener {
    fun onAgentEvent(event: AgentEvent)
}
