package net.hwyz.iov.vehicle.ivi.ivai.speech.config

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * ASR connectivity test (IVI-IVAI-DSN-CR-006 / IVAI-REQ-053).
 *
 * - Android providers: no network is involved → [ConnectionTestResult.Success]
 *   with method "android-local" (the UI only shows the test for remote ones).
 * - HTTP_COMPATIBLE / VENDOR: minimal read-only health GET against the draft
 *   address with the optional Authorization header; 2xx → Success,
 *   401/403 → Unauthorized, otherwise InvalidResponse.
 * - never enters the Agent workflow, never calls a Tool, never auto-saves.
 * - logs (by the caller) must only contain sanitized host/port/duration/result.
 */
class AsrConnectionTester(
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    private val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    private val validator: AsrConfigValidator = AsrConfigValidator()
) {

    suspend fun test(draft: AsrConfigDraft): ConnectionTestResult {
        if (validator.validate(draft) is ValidationResult.Invalid) {
            return ConnectionTestResult.InvalidResponse("地址或必填配置非法")
        }
        return when (draft.providerType) {
            AsrProviderType.ANDROID_ON_DEVICE, AsrProviderType.ANDROID_SYSTEM ->
                ConnectionTestResult.Success("android-local")

            AsrProviderType.HTTP_COMPATIBLE, AsrProviderType.VENDOR -> runRemote(draft)
        }
    }

    private suspend fun runRemote(draft: AsrConfigDraft): ConnectionTestResult {
        val url = draft.baseUrl.trim().toHttpUrlOrNull()
            ?: return ConnectionTestResult.InvalidResponse("地址格式非法")

        val builder = Request.Builder().url(url).get()
        (draft.apiKeyAction as? ApiKeyAction.Replace)?.let { action ->
            builder.header("Authorization", "Bearer ${action.value}")
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

        return try {
            withTimeout(totalTimeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val call = client.newCall(builder.build())
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
        response.code in 200..299 -> ConnectionTestResult.Success("asr-health")
        response.code == 401 || response.code == 403 -> ConnectionTestResult.Unauthorized
        else -> ConnectionTestResult.InvalidResponse("HTTP ${response.code}", "asr-health")
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 3_000L
        const val DEFAULT_READ_TIMEOUT_MS = 10_000L
        const val DEFAULT_TOTAL_TIMEOUT_MS = 10_000L
    }
}
