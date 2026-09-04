package net.hwyz.iov.vehicle.ivi.ivai.model.config

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProviderType
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiChatMessage
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiChatRequest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Result of a connectivity test (IVI-IVAI-DSN-CR-003 / IVAI-REQ-026). */
sealed interface ConnectionTestResult {
    /** [testMethod] marks how the test succeeded ("model-list" or "chat-completions"). */
    data class Success(val testMethod: String? = null) : ConnectionTestResult
    data class NetworkError(val detail: String) : ConnectionTestResult
    data object Timeout : ConnectionTestResult
    data object Unauthorized : ConnectionTestResult
    data class InvalidResponse(val detail: String, val testMethod: String? = null) : ConnectionTestResult
}

/**
 * Minimal read-only connectivity check against the draft config (CR-004):
 *  - Ollama → GET /api/tags
 *  - OpenAI compatible → GET {base}/v1/models first; when the service does not
 *    support a model list, fall back to a minimal, non-tool Chat Completions
 *    POST and mark the test method explicitly
 *  - 3s connect / 10s total timeout, decoupled from real request timeouts
 *  - never triggers the agent workflow or vehicle tools; never saves
 *  - logs (by the caller) must only contain sanitized host/port/duration/result
 */
class ModelConnectionTester(
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    private val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    private val validator: ModelConfigValidator = ModelConfigValidator()
) {

    suspend fun test(draft: ModelConfigDraft): ConnectionTestResult {
        if (validator.validate(draft) is ValidationResult.Invalid) {
            return ConnectionTestResult.InvalidResponse("地址或必填配置非法")
        }
        val url = draft.baseUrl.trim().toHttpUrlOrNull()
            ?: return ConnectionTestResult.InvalidResponse("地址格式非法")

        return when (draft.providerType) {
            ModelProviderType.OLLAMA -> run(url, OLLAMA_HEALTH_PATH, "model-list", method = "GET")
            ModelProviderType.OPENAI_COMPATIBLE -> runOpenAiTest(draft, url)
            else -> ConnectionTestResult.InvalidResponse("不支持的 Provider 类型")
        }
    }

    private suspend fun runOpenAiTest(draft: ModelConfigDraft, baseUrl: okhttp3.HttpUrl): ConnectionTestResult {
        // 1. Prefer the read-only model list.
        val modelsPath = baseUrl.newBuilder().addPathSegments("v1/models").build()
        val first = run(modelsPath, null, "model-list", method = "GET", apiKey = draft.apiKeyAction)
        when (first) {
            is ConnectionTestResult.Success -> return first
            is ConnectionTestResult.Unauthorized -> return first
            is ConnectionTestResult.Timeout -> return first
            is ConnectionTestResult.NetworkError -> return first
            is ConnectionTestResult.InvalidResponse -> {
                // 404/405/400 → service may not expose a model list; fall back.
            }
        }

        // 2. Fallback: minimal non-tool Chat Completions POST, marked explicitly.
        val chatPath = baseUrl.newBuilder().addPathSegments("v1/chat/completions").build()
        val payload = kotlinx.serialization.json.Json.encodeToString(
            OpenAiChatRequest.serializer(),
            OpenAiChatRequest(
                model = draft.modelName ?: "test",
                messages = listOf(OpenAiChatMessage("user", "ping")),
                stream = false,
                temperature = 0.0,
                maxTokens = 1
            )
        )
        return run(
            chatPath,
            null,
            "chat-completions",
            method = "POST",
            apiKey = draft.apiKeyAction,
            body = payload
        )
    }

    private suspend fun run(
        url: okhttp3.HttpUrl,
        healthPath: String?,
        testMethod: String,
        method: String,
        apiKey: ApiKeyAction = ApiKeyAction.Keep,
        body: String? = null
    ): ConnectionTestResult {
        val target = healthPath?.let { url.newBuilder().addPathSegments(it.trimStart('/')).build() } ?: url
        val requestBuilder = Request.Builder().url(target)
        if (method == "POST") {
            requestBuilder.post(
                (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType())
            )
        } else {
            requestBuilder.get()
        }
        (apiKey as? ApiKeyAction.Replace)?.let { action ->
            requestBuilder.header("Authorization", "Bearer ${action.value}")
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

        return try {
            withTimeout(totalTimeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val call = client.newCall(requestBuilder.build())
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (cont.isCancelled) return
                            cont.resume(
                                if (e is SocketTimeoutException) {
                                    ConnectionTestResult.Timeout
                                } else {
                                    ConnectionTestResult.NetworkError(e.message ?: "网络错误")
                                }
                            )
                        }

                        override fun onResponse(call: Call, response: Response) {
                            response.use {
                                if (cont.isCancelled) return
                                cont.resume(mapResponse(it, testMethod))
                            }
                        }
                    })
                }
            }
        } catch (e: TimeoutCancellationException) {
            ConnectionTestResult.Timeout
        } catch (e: SocketTimeoutException) {
            ConnectionTestResult.Timeout
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionTestResult.NetworkError(e.message ?: "网络错误")
        }
    }

    private fun mapResponse(response: Response, testMethod: String): ConnectionTestResult = when {
        response.code in 200..299 -> ConnectionTestResult.Success(testMethod)
        response.code == 401 || response.code == 403 -> ConnectionTestResult.Unauthorized
        else -> ConnectionTestResult.InvalidResponse("HTTP ${response.code}", testMethod)
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 3_000L
        const val DEFAULT_READ_TIMEOUT_MS = 10_000L
        const val DEFAULT_TOTAL_TIMEOUT_MS = 10_000L

        /** Ollama model list endpoint — minimal, read-only. */
        const val OLLAMA_HEALTH_PATH = "/api/tags"
    }
}
