package net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI Chat Completions response (non-streaming). Only the fields needed by
 * the unified [net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse] conversion
 * are declared; unknown fields are tolerated.
 */
@Serializable
data class OpenAiChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
    /** Optional provider timing extension — never equals model-call wall time. */
    @SerialName("timing") val timing: OpenAiTiming? = null,
    @SerialName("provider_time") val providerTimeMs: Long? = null
)

@Serializable
data class OpenAiChoice(
    val index: Int? = null,
    val message: OpenAiResponseMessage? = null,
    val delta: OpenAiDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

/** Streaming chunk payload (choices[].delta.content). */
@Serializable
data class OpenAiDelta(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class OpenAiResponseMessage(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null
)

/** Server-side compute timing, when the compatible service reports one. */
@Serializable
data class OpenAiTiming(
    @SerialName("prompt_eval_ms") val promptEvalMs: Long? = null,
    @SerialName("generation_ms") val generationMs: Long? = null,
    @SerialName("total_ms") val totalMs: Long? = null
)
