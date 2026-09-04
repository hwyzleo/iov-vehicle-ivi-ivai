package net.hwyz.iov.vehicle.ivi.ivai.model

import kotlinx.serialization.json.JsonElement

/**
 * Unified response returned by a [ModelProvider].
 *
 * @param content raw text of the assistant message.content
 * @param contentJson second-level parse of [content] as JSON, or null when it is not valid JSON
 * @param network OkHttp-observed network sub-metrics inside the model call (diagnostic only)
 * @param providerCompute server-side compute data explicitly reported by the provider (diagnostic only)
 */
data class ModelResponse(
    val requestId: String,
    val content: String,
    val contentJson: JsonElement?,
    val model: String?,
    val finishReason: String?,
    val latencyMs: Long,
    val network: HttpNetworkMetrics? = null,
    val providerCompute: ProviderComputeMetrics? = null,
    /** Client-observed time from request start to the first streamed token (streaming only). */
    val timeToFirstTokenMs: Long? = null
)
