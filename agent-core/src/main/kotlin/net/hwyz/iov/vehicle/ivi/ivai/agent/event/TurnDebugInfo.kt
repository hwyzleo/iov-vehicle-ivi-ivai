package net.hwyz.iov.vehicle.ivi.ivai.agent.event

import kotlinx.serialization.Serializable

/**
 * One message as composed for the model request (role + content).
 */
@Serializable
data class ComposedMessage(
    val role: String,
    val content: String
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
 * Per-turn debug details shown in the chat debug panel (IVI-IVAI-DSN-CR-002):
 * prompt orchestration, raw model output, parsed result, triggered tool and
 * performance statistics. Deliberately separate from the stable interaction
 * events; the UI may hide it on release builds.
 */
@Serializable
data class TurnDebugInfo(
    val turnId: String,
    val requestId: String,
    val composedMessages: List<ComposedMessage> = emptyList(),
    val rawModelContent: String? = null,
    val parsed: ParsedOutputSummary? = null,
    val tool: ToolDebugInfo? = null,
    val modelLatencyMs: Long = -1,
    val toolLatencyMs: Long? = null,
    val totalLatencyMs: Long = -1,
    val state: String? = null,
    val route: String? = null,
    val errorCode: String? = null,
    val replayed: Boolean = false
)
