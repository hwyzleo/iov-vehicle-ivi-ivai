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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.hwyz.iov.vehicle.ivi.ivai.model.HttpNetworkMetrics
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelClientException
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelErrorKind
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProviderType
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.model.NetworkMetricsEventListener
import net.hwyz.iov.vehicle.ivi.ivai.model.ProviderComputeMetrics
import net.hwyz.iov.vehicle.ivi.ivai.model.StreamingModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigException
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigSnapshotProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigValidator
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelRuntimeConfig
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretValue
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiChatMessage
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiChatRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiChatResponse
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiErrorResponse
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiUsage
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
 * Configuration for the OpenAI Chat Completions compatible endpoint
 * (IVI-IVAI-DSN-CR-004). Used by the fixed-config constructor for wiring / tests.
 */
data class OpenAICompatibleConfig(
    val baseUrl: String = "http://localhost:8000",
    val endpointPath: String = "/v1/chat/completions",
    val model: String = "qwen3.5:4b",
    val apiKey: String? = null,
    val temperature: Double = 0.0,
    val maxTokens: Int = 512,
    val connectTimeoutMs: Long = 5_000,
    val readTimeoutMs: Long = 60_000
)

/**
 * [ModelProvider] for any OpenAI Chat Completions compatible service
 * (Ollama OpenAI mode, vLLM, LM Studio, gateway…). Non-streaming (CR-004).
 *
 * Responsibilities (IVI-IVAI-DSN-CR-004):
 *  - immutable runtime config snapshot per request (baseUrl + endpointPath +
 *    modelName + apiKey), so config changes take effect on the next request
 *  - Authorization: Bearer <apiKey>; the raw key exists only while building the header
 *  - Base URL + Endpoint Path normalized join — never duplicates /v1 or `//`
 *  - per-request OkHttp EventListener → [HttpNetworkMetrics] (diagnostic only)
 *  - choices[0].message.content → unified [ModelResponse]; missing choices /
 *    missing content / invalid JSON map to IVAI-MODEL-003
 *  - server timing extensions map to [ProviderComputeMetrics], never to wall time
 */
class OpenAiCompatibleModelProvider(
    private val snapshotProvider: ModelConfigSnapshotProvider,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    client: OkHttpClient? = null
) : ModelProvider, StreamingModelProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val validator = ModelConfigValidator()
    private val client: OkHttpClient = client ?: defaultClient(connectTimeoutMs, readTimeoutMs)

    /** Fixed-config provider for simple wiring / tests (no runtime snapshot). */
    constructor(config: OpenAICompatibleConfig) : this(
        snapshotProvider = ModelConfigSnapshotProvider {
            ModelRuntimeConfig(
                baseUrl = config.baseUrl.toHttpUrlOrNull()
                    ?: throw IllegalArgumentException("Invalid OpenAI compatible baseUrl: ${config.baseUrl}"),
                providerType = ModelProviderType.OPENAI_COMPATIBLE,
                endpointPath = config.endpointPath,
                modelName = config.model,
                apiKey = config.apiKey?.let { SecretValue.of(it) },
                version = 0L
            )
        },
        connectTimeoutMs = config.connectTimeoutMs,
        readTimeoutMs = config.readTimeoutMs
    )

    override suspend fun generate(request: ModelRequest): ModelResponse {
        val snapshot = try {
            snapshotProvider.loadSnapshot()
        } catch (e: ModelConfigException) {
            throw ModelClientException(
                kind = ModelErrorKind.CONFIGURATION_ERROR,
                message = "模型配置无效，请先在模型配置中修复: ${e.message}",
                cause = e
            )
        }
        if (snapshot.providerType != ModelProviderType.OPENAI_COMPATIBLE) {
            throw ModelClientException(
                kind = ModelErrorKind.PROVIDER_UNSUPPORTED,
                message = "Provider 类型不受支持：${snapshot.providerType}"
            )
        }
        val modelName = snapshot.modelName ?: request.model

        val startMs = System.currentTimeMillis()
        try {
            return withTimeout(request.timeoutMs) {
                val (rawBody, network) = callChat(request, snapshot, modelName)
                val elapsedMs = System.currentTimeMillis() - startMs
                val response = parseEnvelope(rawBody)
                val providerCompute = computeMetrics(response)
                val content = response.choices
                    .firstOrNull { it.message?.content != null }
                    ?.message
                    ?.content
                if (content == null) {
                    throw ModelClientException(
                        kind = ModelErrorKind.INVALID_RESPONSE,
                        message = "OpenAI 兼容响应缺少有效 choices/content (requestId=${request.requestId})"
                    )
                }
                // JSON 契约容错（CR-007 补充）：小模型偶发把 JSON 输出成自然语言/
                // 带格式漂移 → 需解析为 JSON 对象。纯文本会被 parseToJsonElement 解析成
                // JsonLiteral（不抛异常），因此以“是否为 JSON 对象”为准触发修复重试；
                // 修复成功用修正结果，失败抛 IVAI-MODEL-002。
                var finalContent = content
                var contentJson = parseAsJsonObject(content, request.requestId)
                if (contentJson == null) {
                    val repaired = runCatching {
                        repairContentJson(request, snapshot, modelName, content)
                    }.getOrNull()
                    if (repaired != null) {
                        finalContent = repaired
                        contentJson = parseAsJsonObject(repaired, request.requestId)
                    }
                }
                if (contentJson == null) {
                    throw ModelClientException(
                        kind = ModelErrorKind.RESPONSE_PARSE_ERROR,
                        message = "OpenAI message.content is not a valid JSON object (requestId=${request.requestId})",
                        rawContent = finalContent
                    )
                }
                ModelResponse(
                    requestId = request.requestId,
                    content = finalContent,
                    contentJson = contentJson,
                    model = response.model,
                    finishReason = response.choices.firstOrNull()?.finishReason,
                    latencyMs = elapsedMs,
                    network = network,
                    providerCompute = providerCompute
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw ModelClientException(
                kind = ModelErrorKind.TIMEOUT,
                message = "Model request timed out after ${request.timeoutMs}ms",
                cause = e
            )
        } catch (e: CancellationException) {
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
                message = "Failed to call OpenAI compatible API: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Streaming variant (stream=true, SSE `data:` frames). Reads frames on
     * Dispatchers.IO, forwards each content delta via [onDelta] and returns the
     * fully accumulated response with client-observed time-to-first-token.
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
        if (snapshot.providerType != ModelProviderType.OPENAI_COMPATIBLE) {
            throw ModelClientException(
                kind = ModelErrorKind.PROVIDER_UNSUPPORTED,
                message = "Provider 类型不受支持：${snapshot.providerType}"
            )
        }
        val modelName = snapshot.modelName ?: request.model
        val startMs = System.currentTimeMillis()
        val startNs = System.nanoTime()
        try {
            return withTimeout(request.timeoutMs) {
                withContext(Dispatchers.IO) {
                    val listener = NetworkMetricsEventListener()
                    val httpClient = client.newBuilder().eventListener(listener).build()
                    val httpRequest = buildHttpRequest(request, snapshot, modelName, stream = true)
                    val call = httpClient.newCall(httpRequest)
                    currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw ModelClientException(
                                kind = ModelErrorKind.HTTP_ERROR,
                                httpCode = response.code,
                                message = "OpenAI compatible streaming HTTP error: ${response.code} ${errorSummary(response)}"
                            )
                        }
                        val source = response.body?.source()
                            ?: throw ModelClientException(
                                kind = ModelErrorKind.RESPONSE_PARSE_ERROR,
                                message = "OpenAI compatible streaming body is empty"
                            )
                        val full = StringBuilder()
                        var ttftNs: Long? = null
                        var model: String? = null
                        var finishReason: String? = null
                        var usage: OpenAiUsage? = null
                        var timing: net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiTiming? = null
                        var providerTimeMs: Long? = null
                        var gotContent = false
                        while (true) {
                            val frame = readSseFrame(source) ?: break
                            val data = frame.trim()
                            if (data.isEmpty()) continue
                            if (data == SSE_DONE_MARKER) break
                            val chunk = parseEnvelope(data)
                            model = chunk.model ?: model
                            usage = chunk.usage ?: usage
                            timing = chunk.timing ?: timing
                            providerTimeMs = chunk.providerTimeMs ?: providerTimeMs
                            finishReason = chunk.choices.firstOrNull()?.finishReason ?: finishReason
                            val delta = chunk.choices.firstOrNull()?.delta?.content
                            if (delta != null) {
                                gotContent = true
                                if (ttftNs == null) ttftNs = System.nanoTime()
                                full.append(delta)
                                onDelta(delta)
                            }
                        }
                        if (!gotContent && full.isEmpty()) {
                            throw ModelClientException(
                                kind = ModelErrorKind.INVALID_RESPONSE,
                                message = "OpenAI 兼容流式响应缺少有效 choices/delta.content (requestId=${request.requestId})"
                            )
                        }
                        val elapsedMs = System.currentTimeMillis() - startMs
                        ModelResponse(
                            requestId = request.requestId,
                            content = full.toString(),
                            contentJson = parseContentJson(full.toString(), request.requestId),
                            model = model,
                            finishReason = finishReason,
                            latencyMs = elapsedMs,
                            network = listener.snapshot(),
                            providerCompute = buildComputeMetrics(usage, timing, providerTimeMs),
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
                message = "Failed to stream OpenAI compatible API: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Reads one SSE frame: accumulates `data:` lines until a blank line, returns
     * their joined payload; null on EOF with nothing accumulated.
     */
    private fun readSseFrame(source: BufferedSource): String? {
        val parts = mutableListOf<String>()
        while (true) {
            val line = source.readUtf8Line() ?: return if (parts.isEmpty()) null else parts.joinToString("\n")
            if (line.startsWith("data:")) {
                parts += line.removePrefix("data:").trim()
            } else if (line.isBlank() && parts.isNotEmpty()) {
                return parts.joinToString("\n")
            }
        }
    }

    private suspend fun callChat(
        request: ModelRequest,
        snapshot: ModelRuntimeConfig,
        modelName: String
    ): Pair<String, HttpNetworkMetrics?> {
        val payload = OpenAiChatRequest(
            model = modelName,
            messages = request.messages.map { OpenAiChatMessage(it.role, it.content) },
            stream = false,
            temperature = request.temperature,
            maxTokens = request.maxTokens
        )
        return executeChat(payload, snapshot, request.requestId)
    }

    /** Executes one non-streaming chat completion against the configured endpoint. */
    private suspend fun executeChat(
        payload: OpenAiChatRequest,
        snapshot: ModelRuntimeConfig,
        requestId: String
    ): Pair<String, HttpNetworkMetrics?> {
        val listener = NetworkMetricsEventListener()
        val httpClient = client.newBuilder().eventListener(listener).build()
        val httpRequest = buildHttpRequestFromPayload(payload, snapshot, requestId)
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
                                    message = "OpenAI compatible HTTP error: ${it.code} ${errorSummary(it)}"
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
        modelName: String,
        stream: Boolean = false
    ): Request {
        val payload = OpenAiChatRequest(
            model = modelName,
            messages = request.messages.map { OpenAiChatMessage(it.role, it.content) },
            stream = stream,
            temperature = request.temperature,
            maxTokens = request.maxTokens
        )
        return buildHttpRequestFromPayload(payload, snapshot, request.requestId, stream)
    }

    private fun buildHttpRequestFromPayload(
        payload: OpenAiChatRequest,
        snapshot: ModelRuntimeConfig,
        requestId: String,
        stream: Boolean = false
    ): Request {
        val endpoint = validator.joinEndpointPath(
            snapshot.baseUrl.toString(),
            snapshot.endpointPath ?: DEFAULT_ENDPOINT_PATH
        )
        // 序列化后合并顶层字段：确保 enable_thinking 显式下发（kotlinx 默认会省略
        // 等于默认值的字段，而 SiliconFlow 推理模型默认可能开启思考）。
        val bodyJson = buildJsonObject {
            json.encodeToJsonElement(OpenAiChatRequest.serializer(), payload)
                .jsonObject.forEach { (k, v) -> put(k, v) }
            put("enable_thinking", payload.enableThinking)
        }
        val builder = Request.Builder()
            .url(endpoint)
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .header("X-IVAI-Request-Id", requestId)
        snapshot.apiKey?.use { key ->
            // The raw key exists only for this header construction; logs must mask it.
            builder.header("Authorization", "Bearer $key")
        }
        return builder.build()
    }

    private fun parseEnvelope(rawBody: String): OpenAiChatResponse {
        return try {
            json.decodeFromString(OpenAiChatResponse.serializer(), rawBody)
        } catch (e: Exception) {
            throw ModelClientException(
                kind = ModelErrorKind.RESPONSE_PARSE_ERROR,
                message = "OpenAI compatible response parse failed: ${e.message}",
                cause = e,
                rawContent = rawBody
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
            message = "OpenAI message.content is not valid JSON (requestId=$requestId): ${e.message}",
            cause = e,
            rawContent = content
        )
    }

    /**
     * Parses [content] as a JSON **object** (the Agent output contract is a top-level
     * object). Returns null for parse failures, natural-language text, arrays or
     * any other non-object JSON — the caller uses this to decide on JSON repair.
     */
    private fun parseAsJsonObject(content: String, requestId: String): JsonObject? {
        val cleaned = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching { json.parseToJsonElement(cleaned) }
            .getOrNull()
            ?.let { it as? JsonObject }
    }

    /**
     * JSON 修复重试（IVI-IVAI-DSN-CR-007 补充）：主请求的 message.content 不是合法
     * JSON 时，把内容发回模型，要求仅输出修正后的 JSON（temperature=0 降低随机性）。
     * 修复请求同样受外层 withTimeout 总时限约束，最多尝试一次；返回修复后的内容文本。
     */
    private suspend fun repairContentJson(
        request: ModelRequest,
        snapshot: ModelRuntimeConfig,
        modelName: String,
        badContent: String
    ): String {
        val repairPayload = OpenAiChatRequest(
            model = modelName,
            messages = listOf(
                OpenAiChatMessage("system", REPAIR_SYSTEM_PROMPT),
                OpenAiChatMessage("user", "待修正的内容：\n" + badContent.take(REPAIR_INPUT_CHARS))
            ),
            stream = false,
            temperature = 0.0,
            maxTokens = REPAIR_MAX_TOKENS
        )
        val (rawBody, _) = executeChat(repairPayload, snapshot, request.requestId)
        val response = parseEnvelope(rawBody) // 修复响应外层也必须是合法 JSON
        val content = response.choices.firstOrNull { it.message?.content != null }?.message?.content
            ?: throw ModelClientException(
                kind = ModelErrorKind.INVALID_RESPONSE,
                message = "JSON 修复响应缺少有效 choices/content (requestId=${request.requestId})"
            )
        return content
    }

    /** Token usage is an extension metric only — never treated as compute time. */
    private fun computeMetrics(response: OpenAiChatResponse): ProviderComputeMetrics? {
        val timing = response.timing
        val usage = response.usage
        val source = "openai_compatible"
        if (timing == null && response.providerTimeMs == null && usage == null) return null
        return ProviderComputeMetrics(
            promptEvaluationMs = timing?.promptEvalMs,
            generationMs = timing?.generationMs,
            totalReportedMs = timing?.totalMs ?: response.providerTimeMs,
            promptEvaluationTokens = usage?.promptTokens?.toLong(),
            generationTokens = usage?.completionTokens?.toLong(),
            source = source
        )
    }

    /** Builds [ProviderComputeMetrics] from streamed usage / timing fields. */
    private fun buildComputeMetrics(
        usage: OpenAiUsage?,
        timing: net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiTiming?,
        providerTimeMs: Long?
    ): ProviderComputeMetrics? {
        val source = "openai_compatible"
        if (timing == null && providerTimeMs == null && usage == null) return null
        return ProviderComputeMetrics(
            promptEvaluationMs = timing?.promptEvalMs,
            generationMs = timing?.generationMs,
            totalReportedMs = timing?.totalMs ?: providerTimeMs,
            promptEvaluationTokens = usage?.promptTokens?.toLong(),
            generationTokens = usage?.completionTokens?.toLong(),
            source = source
        )
    }

    private fun errorSummary(response: Response): String {
        return try {
            val raw = response.body?.string().orEmpty()
            if (raw.isBlank()) "" else " - ${json.decodeFromString(OpenAiErrorResponse.serializer(), raw).error?.message ?: raw.take(200)}"
        } catch (_: Exception) {
            ""
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L
        const val DEFAULT_READ_TIMEOUT_MS = 60_000L
        const val DEFAULT_ENDPOINT_PATH = "/v1/chat/completions"
        const val NANOS_PER_MILLI = 1_000_000L
        const val SSE_DONE_MARKER = "[DONE]"

        /** 修复请求的上限输出 token（Agent JSON 契约通常远小于此）。 */
        const val REPAIR_MAX_TOKENS = 1_024

        /** 坏 JSON 内容最多回传的字符数，避免超长请求。 */
        const val REPAIR_INPUT_CHARS = 2_000

        const val REPAIR_SYSTEM_PROMPT =
            "你是 JSON 修复助手。我会给你一段内容，它本应是合法的 JSON 对象，但可能" +
                "夹带了自然语言、Markdown 或格式错误。请只输出修正后的合法 JSON 对象，" +
                "不得包含任何解释、说明或代码围栏。"

        fun defaultClient(connectTimeoutMs: Long, readTimeoutMs: Long): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
    }
}
