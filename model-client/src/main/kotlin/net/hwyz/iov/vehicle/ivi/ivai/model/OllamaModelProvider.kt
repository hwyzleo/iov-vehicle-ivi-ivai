package net.hwyz.iov.vehicle.ivi.ivai.model

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigException
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigSnapshotProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelRuntimeConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * [ModelProvider] that calls a local / remote Ollama Chat API (non-streaming).
 *
 * Responsibilities (IVI-IVAI-DSN-CR-001 + CR-003):
 *  - captures an immutable runtime config snapshot at the start of each request
 *    (baseUrl + apiKey + configVersion) so config changes never affect in-flight
 *    requests and take effect for the next one without a restart (IVAI-REQ-025)
 *  - writes Authorization per the provider protocol when an apiKey is set;
 *    local Ollama (no key) simply omits it
 *  - base URL, model name and generation parameters; request timeout, cancellation
 *    and requestId propagation
 *  - Ollama outer response parse + second-level JSON parse of message.content
 *  - unified conversion of network / HTTP / model / config / parse errors
 */
class OllamaModelProvider(
    private val snapshotProvider: ModelConfigSnapshotProvider,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    client: OkHttpClient? = null
) : ModelProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient = client ?: defaultClient(connectTimeoutMs, readTimeoutMs)

    /** Fixed-config provider for simple wiring / tests (no runtime snapshot). */
    constructor(config: OllamaConfig) : this(
        snapshotProvider = ModelConfigSnapshotProvider {
            ModelRuntimeConfig(
                baseUrl = config.baseUrl.toHttpUrlOrNull()
                    ?: throw IllegalArgumentException("Invalid Ollama baseUrl: ${config.baseUrl}"),
                apiKey = null,
                version = 0L
            )
        },
        connectTimeoutMs = config.connectTimeoutMs,
        readTimeoutMs = config.readTimeoutMs
    )

    override suspend fun generate(request: ModelRequest): ModelResponse {
        // Immutable snapshot for the whole request — never swapped mid-flight.
        val snapshot = try {
            snapshotProvider.loadSnapshot()
        } catch (e: ModelConfigException) {
            throw ModelClientException(
                kind = ModelErrorKind.CONFIGURATION_ERROR,
                message = "模型配置无效，请先在模型配置中修复: ${e.message}",
                cause = e
            )
        }

        val startMs = System.currentTimeMillis()
        try {
            return withTimeout(request.timeoutMs) {
                val rawBody = callChat(request, snapshot)
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

    private suspend fun callChat(request: ModelRequest, snapshot: ModelRuntimeConfig): String {
        val httpRequest = buildHttpRequest(request, snapshot)
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

    private fun buildHttpRequest(request: ModelRequest, snapshot: ModelRuntimeConfig): Request {
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

        val url = snapshot.baseUrl.newBuilder()
            .addPathSegments("api/chat")
            .build()

        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("X-IVAI-Request-Id", request.requestId)
        snapshot.apiKey?.use { key ->
            // Provider protocol header; the raw key exists only for this construction.
            builder.header("Authorization", "Bearer $key")
        }
        return builder.build()
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
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L
        const val DEFAULT_READ_TIMEOUT_MS = 60_000L

        fun defaultClient(connectTimeoutMs: Long, readTimeoutMs: Long): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
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
