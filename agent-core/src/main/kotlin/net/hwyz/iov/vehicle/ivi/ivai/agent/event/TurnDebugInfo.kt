package net.hwyz.iov.vehicle.ivi.ivai.agent.event

import kotlinx.serialization.Serializable
import net.hwyz.iov.vehicle.ivi.ivai.model.AgentPerformanceMetrics

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
 * page serves the prompt snapshot instead.
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
    val replayed: Boolean = false
)
