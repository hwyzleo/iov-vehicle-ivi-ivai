package net.hwyz.iov.vehicle.ivi.ivai.model

/**
 * A single chat message exchanged with a model provider.
 */
data class ChatMessage(
    val role: String,
    val content: String
)

/**
 * Unified request payload sent to any [ModelProvider].
 */
data class ModelRequest(
    val requestId: String,
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.0,
    val maxTokens: Int = 512,
    val timeoutMs: Long = 30_000
)
