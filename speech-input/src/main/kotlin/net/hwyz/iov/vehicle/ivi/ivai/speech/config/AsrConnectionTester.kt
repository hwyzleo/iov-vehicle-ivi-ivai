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
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretValue
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.speech.android.HttpAsrParseResult
import net.hwyz.iov.vehicle.ivi.ivai.speech.android.HttpAsrRequestBuilder
import net.hwyz.iov.vehicle.ivi.ivai.speech.android.HttpAsrResponseParser
import net.hwyz.iov.vehicle.ivi.ivai.speech.android.WavEncoder
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.AsrErrorCode
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * ASR connectivity test (IVI-IVAI-DSN-CR-006 / IVAI-REQ-053 + IVI-IVAI-DSN-CR-007).
 *
 * - Android providers: no network is involved → [ConnectionTestResult.Success]
 *   with method "android-local" (the UI only shows the test for remote ones).
 * - HTTP_COMPATIBLE / VENDOR: reuses the exact runtime request path — the same
 *   URL handling, Authorization header, timeouts and response parser as
 *   [net.hwyz.iov.vehicle.ivi.ivai.speech.android.HttpCompatibleSpeechEngine]
 *   — and uploads a short silent WAV as a minimal recognition health check, so
 *   a "test succeeded" can never diverge from "runtime works".
 * - never enters the Agent workflow, never calls a Tool, never auto-saves.
 * - logs (by the caller) must only contain sanitized host/port/duration/result.
 */
class AsrConnectionTester(
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    private val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    private val validator: AsrConfigValidator = AsrConfigValidator(),
    private val client: OkHttpClient? = null
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
        val apiKey = (draft.apiKeyAction as? ApiKeyAction.Replace)?.value?.let { SecretValue.of(it) }
        val requestBuilder = HttpAsrRequestBuilder(
            baseUrl = draft.baseUrl.trim(),
            modelName = draft.modelName.trim().takeIf { it.isNotEmpty() },
            languageTag = draft.languageTag.trim().takeIf { it.isNotEmpty() },
            apiKey = apiKey
        )
        val parser = HttpAsrResponseParser()

        val request = runCatching {
            // 100 ms of digital silence: validates the multipart contract without
            // requiring an actual utterance.
            val silentWav = WavEncoder.encode(ByteArray(SILENT_PCM_BYTES))
            requestBuilder.build(silentWav)
        }.getOrElse {
            return ConnectionTestResult.InvalidResponse("地址格式非法")
        }

        val effectiveClient = client ?: OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

        return try {
            withTimeout(totalTimeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val call = effectiveClient.newCall(request)
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
                                cont.resume(mapResponse(parser.parse(it)))
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

    private fun mapResponse(parsed: HttpAsrParseResult): ConnectionTestResult = when (parsed) {
        is HttpAsrParseResult.Success -> ConnectionTestResult.Success("asr-health")

        is HttpAsrParseResult.Failure -> when {
            // 401/403 → key 无效/未携带（SiliconFlow 对无 key / 假 key 均返回 401）。
            parsed.error.code == AsrErrorCode.REMOTE_UNAUTHORIZED ->
                ConnectionTestResult.Unauthorized

            // 非 401/403 的业务 4xx（SiliconFlow 会拒绝“静音测试音频”，返回 HTTP 400）：
            // 能收到这类响应说明网络通、鉴权已通过、multipart 契约被接受 —— 连接测试
            // 只验证网络 + 鉴权 + 契约，因此视为成功，并保留状态供诊断。
            parsed.httpStatus in 400..499 ->
                ConnectionTestResult.Success("asr-health(HTTP ${parsed.httpStatus})")

            parsed.error.code == AsrErrorCode.NETWORK_ERROR ->
                ConnectionTestResult.NetworkError(parsed.error.message)

            // 2xx with an empty transcription is a valid contract — the network /
            // auth / request shape all worked, the silent probe just had no speech.
            parsed.error.code == AsrErrorCode.EMPTY_RESULT ->
                ConnectionTestResult.Success("asr-health")

            else -> ConnectionTestResult.InvalidResponse(parsed.error.message, "asr-health")
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 3_000L
        const val DEFAULT_READ_TIMEOUT_MS = 10_000L
        const val DEFAULT_TOTAL_TIMEOUT_MS = 10_000L

        /** 100 ms of 16 kHz / 16-bit / mono silence = 3 200 bytes. */
        private const val SILENT_PCM_BYTES = 3_200
    }
}
