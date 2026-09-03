package net.hwyz.iov.vehicle.ivi.ivai.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [ModelProvider] that calls a local / remote Ollama Chat API (non-streaming).
 *
 * Responsibilities (IVI-IVAI-DSN-CR-001):
 *  - base URL, model name and generation parameters
 *  - request timeout, cancellation and requestId propagation
 *  - Ollama outer response parse + second-level JSON parse of message.content
 *  - unified conversion of network / HTTP / model / parse errors
 */
class OllamaModelProvider(
    private val config: OllamaConfig,
    private val client: OkHttpClient = defaultClient(config)
) : ModelProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun generate(request: ModelRequest): ModelResponse {
        val startMs = System.currentTimeMillis()
        try {
            return withTimeout(request.timeoutMs) {
                val rawBody = callChat(request)
                val elapsedMs = System.currentTimeMillis() - startMs
                val envelope = parseEnvelope(rawBody)
                val content = envelope.message?.content.orEmpty()
                val contentJson = parseContentJson(content, request.requestId)
                ModelResponse(
                    requestId = request.requestId,
                    content = content,
                    contentJson = contentJson,
                    model = envelope.model,
                    finishReason = envelope.doneReason,
                    latencyMs = elapsedMs
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw ModelClientException(
                kind = ModelErrorKind.TIMEOUT,
                message = "Model request timed out after ${request.timeoutMs}ms",
                cause = e
            )
        } catch (e: CancellationException) {
            // Cooperative cancellation: propagate to the caller.
            throw e
        } catch (e: ModelClientException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ModelClientException(
                kind = ModelErrorKind.TIMEOUT,
                message = "Model request timed out: ${e.message}",
                cause = e
            )
        } catch (e: Exception) {
            throw ModelClientException(
                kind = ModelErrorKind.NETWORK_UNAVAILABLE,
                message = "Failed to call Ollama: ${e.message}",
                cause = e
            )
        }
    }

    private suspend fun callChat(request: ModelRequest): String {
        val httpRequest = buildHttpRequest(request)
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(httpRequest)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            if (cont.isCancelled) return
                            cont.resumeWithException(
                                ModelClientException(
                                    kind = ModelErrorKind.HTTP_ERROR,
                                    httpCode = it.code,
                                    message = "Ollama HTTP error: ${it.code}"
                                )
                            )
                            return
                        }
                        val body = it.body?.string().orEmpty()
                        if (cont.isCancelled) return
                        cont.resume(body)
                    }
                }
            })
        }
    }

    private fun buildHttpRequest(request: ModelRequest): Request {
        val payload = buildJsonObject {
            put("model", request.model)
            put("stream", false)
            // qwen3.5 等模型默认开启 thinking（reasoning），会先输出大段思考过程再作答，
            // 对工具调用场景明显拖慢响应、易触发超时；这里显式关闭。
            put("think", false)
            putJsonArray("messages") {
                request.messages.forEach { msg ->
                    add(
                        buildJsonObject {
                            put("role", msg.role)
                            put("content", msg.content)
                        }
                    )
                }
            }
            put(
                "options",
                buildJsonObject {
                    put("temperature", request.temperature)
                    put("num_predict", request.maxTokens)
                }
            )
        }
        return Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/api/chat")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("X-IVAI-Request-Id", request.requestId)
            .build()
    }

    private fun parseEnvelope(rawBody: String): OllamaEnvelope {
        return try {
            json.decodeFromString(OllamaEnvelope.serializer(), rawBody)
        } catch (e: Exception) {
            throw ModelClientException(
                kind = ModelErrorKind.RESPONSE_PARSE_ERROR,
                message = "Ollama outer response parse failed: ${e.message}",
                cause = e
            )
        }
    }

    private fun parseContentJson(content: String, requestId: String) = try {
        // Tolerate markdown code fences the model may wrap around the JSON.
        val cleaned = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        json.parseToJsonElement(cleaned)
    } catch (e: Exception) {
        throw ModelClientException(
            kind = ModelErrorKind.RESPONSE_PARSE_ERROR,
            message = "Ollama message.content is not valid JSON (requestId=$requestId): ${e.message}",
            cause = e
        )
    }

    private companion object {
        fun defaultClient(config: OllamaConfig): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
    }
}

@Serializable
private data class OllamaEnvelope(
    val model: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean? = null,
    @SerialName("done_reason") val doneReason: String? = null
)

@Serializable
private data class OllamaMessage(
    val role: String? = null,
    val content: String = ""
)
