package net.hwyz.iov.vehicle.ivi.ivai.model

import kotlinx.serialization.json.JsonElement

/**
 * Unified response returned by a [ModelProvider].
 *
 * @param content raw text of the assistant message.content
 * @param contentJson second-level parse of [content] as JSON, or null when it is not valid JSON
 */
data class ModelResponse(
    val requestId: String,
    val content: String,
    val contentJson: JsonElement?,
    val model: String?,
    val finishReason: String?,
    val latencyMs: Long
)
