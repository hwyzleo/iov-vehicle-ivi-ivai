package net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal non-streaming Chat Completions request (IVI-IVAI-DSN-CR-004).
 * stream=false and temperature=0 keep the response deterministic and simple;
 * the Responses API and streaming token UI are out of scope for v0.1.
 */
@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiChatMessage>,
    val stream: Boolean = false,
    val temperature: Double = 0.0,
    @SerialName("max_tokens") val maxTokens: Int? = null
)

@Serializable
data class OpenAiChatMessage(
    val role: String,
    val content: String
)
