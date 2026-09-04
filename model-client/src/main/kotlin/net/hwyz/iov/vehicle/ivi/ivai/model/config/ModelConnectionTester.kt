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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Result of a connectivity test (IVI-IVAI-DSN-CR-003 / IVAI-REQ-026). */
sealed interface ConnectionTestResult {
    data object Success : ConnectionTestResult
    data class NetworkError(val detail: String) : ConnectionTestResult
    data object Timeout : ConnectionTestResult
    data object Unauthorized : ConnectionTestResult
    data class InvalidResponse(val detail: String) : ConnectionTestResult
}

/**
 * Minimal read-only connectivity check against the draft config:
 *  - 3s connect / 10s total timeout, decoupled from real request timeouts
 *  - never triggers the agent workflow or vehicle tools
 *  - does not save; a successful test never auto-applies the draft
 *  - logs (by the caller) must only contain sanitized host/port/duration/result
 */
class ModelConnectionTester(
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    private val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    private val healthPath: String = DEFAULT_HEALTH_PATH,
    private val validator: ModelConfigValidator = ModelConfigValidator()
) {

    suspend fun test(draft: ModelConfigDraft): ConnectionTestResult {
        if (validator.validate(draft) is ValidationResult.Invalid) {
            return ConnectionTestResult.InvalidResponse("地址格式非法")
        }
        val url = draft.baseUrl.trim().toHttpUrlOrNull()
            ?: return ConnectionTestResult.InvalidResponse("地址格式非法")

        val requestBuilder = Request.Builder()
            .url(url.newBuilder().addPathSegments(healthPath.trimStart('/')).build())
            .get()
        (draft.apiKeyAction as? ApiKeyAction.Replace)?.let { action ->
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
                            // OkHttp connect/read timeouts surface as SocketTimeoutException — report as Timeout.
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
                                cont.resume(mapResponse(it))
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

    private fun mapResponse(response: Response): ConnectionTestResult = when {
        response.code in 200..299 -> ConnectionTestResult.Success
        response.code == 401 || response.code == 403 -> ConnectionTestResult.Unauthorized
        else -> ConnectionTestResult.InvalidResponse("HTTP ${response.code}")
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 3_000L
        const val DEFAULT_READ_TIMEOUT_MS = 10_000L
        const val DEFAULT_TOTAL_TIMEOUT_MS = 10_000L

        /** Ollama model list endpoint — minimal, read-only. */
        const val DEFAULT_HEALTH_PATH = "/api/tags"
    }
}
