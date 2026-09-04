package net.hwyz.iov.vehicle.ivi.ivai.model.provider

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.hwyz.iov.vehicle.ivi.ivai.model.HttpNetworkMetrics
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelClientException
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelErrorKind
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.model.NetworkMetricsEventListener
import net.hwyz.iov.vehicle.ivi.ivai.model.ProviderComputeMetrics
import net.hwyz.iov.vehicle.ivi.ivai.model.StreamingModelProvider
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
import okio.BufferedSource

/**
 * [ModelProvider] that calls a local / remote Ollama Chat API (non-streaming).
 *
 * Responsibilities (IVI-IVAI-DSN-CR-001 + CR-003 + CR-004):
 *  - captures an immutable runtime config snapshot at the start of each request
 *    (baseUrl + apiKey + configVersion) so config changes never affect in-flight
 *    requests and take effect for the next one without a restart (IVAI-REQ-025)
 *  - writes Authorization per the provider protocol when an apiKey is set;
 *    local Ollama (no key) simply omits it
 *  - base URL, model name and generation parameters; request timeout, cancellation
 *    and requestId propagation
 *  - per-request OkHttp EventListener → [HttpNetworkMetrics] (diagnostic only)
 *  - Ollama outer response parse + second-level JSON parse of message.content
 *  - unified conversion of network / HTTP / model / config / parse errors
 */
class OllamaModelProvider(
    private val snapshotProvider: ModelConfigSnapshotProvider,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    client: OkHttpClient? = null
) : ModelProvider, StreamingModelProvider {

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
                val (rawBody, network) = callChat(request, snapshot)
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
                    latencyMs = elapsedMs,
                    network = network,
                    providerCompute = envelope.toProviderCompute()
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

    /**
     * Streaming variant (stream=true, NDJSON lines). Reads each line on
     * Dispatchers.IO, forwards the token delta via [onDelta] as it arrives and
     * returns the fully accumulated response with client-observed
     * time-to-first-token and server-side compute stats (eval token counts).
     */
    override suspend fun generateStreaming(
        request: ModelRequest,
        onDelta: suspend (String) -> Unit
    ): ModelResponse {
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
        val startNs = System.nanoTime()
        try {
            return withTimeout(request.timeoutMs) {
                withContext(Dispatchers.IO) {
                    val listener = NetworkMetricsEventListener()
                    val httpClient = client.newBuilder().eventListener(listener).build()
                    val httpRequest = buildHttpRequest(request, snapshot, stream = true)
                    val call = httpClient.newCall(httpRequest)
                    currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw ModelClientException(
                                kind = ModelErrorKind.HTTP_ERROR,
                                httpCode = response.code,
                                message = "Ollama streaming HTTP error: ${response.code}"
                            )
                        }
                        val source = response.body?.source()
                            ?: throw ModelClientException(
                                kind = ModelErrorKind.RESPONSE_PARSE_ERROR,
                                message = "Ollama streaming body is empty"
                            )
                        val full = StringBuilder()
                        var ttftNs: Long? = null
                        var lastEnvelope: OllamaEnvelope? = null
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            val envelope = parseEnvelope(line)
                            lastEnvelope = envelope
                            val delta = envelope.message?.content.orEmpty()
                            if (delta.isNotEmpty()) {
                                if (ttftNs == null) ttftNs = System.nanoTime()
                                full.append(delta)
                                onDelta(delta)
                            }
                            if (envelope.done == true) break
                        }
                        val elapsedMs = System.currentTimeMillis() - startMs
                        ModelResponse(
                            requestId = request.requestId,
                            content = full.toString(),
                            contentJson = parseContentJson(full.toString(), request.requestId),
                            model = lastEnvelope?.model,
                            finishReason = lastEnvelope?.doneReason,
                            latencyMs = elapsedMs,
                            network = listener.snapshot(),
                            providerCompute = lastEnvelope?.toProviderCompute(),
                            timeToFirstTokenMs = ttftNs?.let { (it - startNs) / NANOS_PER_MILLI }
                        )
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw ModelClientException(
                kind = ModelErrorKind.TIMEOUT,
                message = "Model stream timed out after ${request.timeoutMs}ms",
                cause = e
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelClientException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ModelClientException(
                kind = ModelErrorKind.TIMEOUT,
                message = "Model stream timed out: ${e.message}",
                cause = e
            )
        } catch (e: Exception) {
            throw ModelClientException(
                kind = ModelErrorKind.NETWORK_UNAVAILABLE,
                message = "Failed to stream Ollama: ${e.message}",
                cause = e
            )
        }
    }

    private suspend fun callChat(
        request: ModelRequest,
        snapshot: ModelRuntimeConfig
    ): Pair<String, HttpNetworkMetrics?> {
        val listener = NetworkMetricsEventListener()
        val httpClient = client.newBuilder().eventListener(listener).build()
        val httpRequest = buildHttpRequest(request, snapshot)
        val body = suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(httpRequest)
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
                        val raw = it.body?.string().orEmpty()
                        if (cont.isCancelled) return
                        cont.resume(raw)
                    }
                }
            })
        }
        return body to listener.snapshot()
    }

    private fun buildHttpRequest(
        request: ModelRequest,
        snapshot: ModelRuntimeConfig,
        stream: Boolean = false
    ): Request {
        val payload = buildJsonObject {
            put("model", request.model)
            put("stream", stream)
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
        const val NANOS_PER_MILLI = 1_000_000L

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
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("eval_count") val evalCount: Long? = null,
    @SerialName("eval_duration") val evalDurationNs: Long? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Long? = null,
    @SerialName("prompt_eval_duration") val promptEvalDurationNs: Long? = null,
    @SerialName("total_duration") val totalDurationNs: Long? = null
) {
    /**
     * Ollama explicitly reports server-side compute durations (ns) and token
     * counts — diagnostic fields, never added to the client-side model-call total.
     */
    fun toProviderCompute(): ProviderComputeMetrics? {
        val promptEvalMs = promptEvalDurationNs?.let { it / 1_000_000L }
        val generationMs = evalDurationNs?.let { it / 1_000_000L }
        val totalMs = totalDurationNs?.let { it / 1_000_000L }
        if (promptEvalMs == null && generationMs == null && totalMs == null &&
            evalCount == null && promptEvalCount == null
        ) {
            return null
        }
        return ProviderComputeMetrics(
            promptEvaluationMs = promptEvalMs,
            generationMs = generationMs,
            totalReportedMs = totalMs,
            promptEvaluationTokens = promptEvalCount,
            generationTokens = evalCount,
            source = "ollama"
        )
    }
}

@Serializable
private data class OllamaMessage(
    val role: String? = null,
    val content: String = ""
)
