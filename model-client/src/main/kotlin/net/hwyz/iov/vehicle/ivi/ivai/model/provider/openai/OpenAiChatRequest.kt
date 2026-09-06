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
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /**
     * 推理模式开关（SiliconFlow Chat Completions 顶层字段）。false 关闭思考，
     * 与 Ollama 原生 provider 的 think=false 对齐，避免小模型先输出大段
     * reasoning 拖慢响应/造成 content 为空。默认 false，由请求体显式下发。
     */
    @SerialName("enable_thinking") val enableThinking: Boolean = false
)

@Serializable
data class OpenAiChatMessage(
    val role: String,
    val content: String
)
