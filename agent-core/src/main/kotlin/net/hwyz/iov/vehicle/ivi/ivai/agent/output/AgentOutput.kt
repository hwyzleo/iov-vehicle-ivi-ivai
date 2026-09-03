package net.hwyz.iov.vehicle.ivi.ivai.agent.output

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Unified structured model output (IVI-IVAI-DSN-CR-001).
 */
@Serializable
data class AgentOutput(
    val route: String = "",
    val intents: List<Intent> = emptyList(),
    val modelConfidence: Double = 0.0,
    val riskLevel: String = "low",
    val needConfirmation: Boolean = false,
    val missingArguments: List<String> = emptyList(),
    val reasonCode: String? = null
)

@Serializable
data class Intent(
    val toolId: String,
    val functionId: String? = null,
    val arguments: Map<String, JsonElement> = emptyMap()
)

/**
 * The four supported routes (IVI-IVAI-SPEC需求).
 */
enum class AgentRoute {
    LOCAL_TOOL,
    LOCAL_DIALOGUE,
    CLOUD_AI,
    REJECT;

    companion object {
        fun from(value: String): AgentRoute? =
            entries.firstOrNull { it.name == value.trim().uppercase() }
    }
}
