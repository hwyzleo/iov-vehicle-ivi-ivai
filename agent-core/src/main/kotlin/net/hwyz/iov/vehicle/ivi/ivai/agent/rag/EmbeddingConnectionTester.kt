package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Embedding 端点只读连通性测试（CR-011 配置落地）：向 {baseUrl}/embeddings 发送
 * 一条最小查询，校验服务可达、鉴权与返回格式。
 *  - 3s connect / 10s total 超时，与真实请求超时解耦；
 *  - 只做连通性探测，不触发索引构建、不保存任何配置；
 *  - 日志只记录脱敏 host/port/耗时/结果（由调用方负责）。
 */
class EmbeddingConnectionTester(
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    private val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    private val validator: EmbeddingConfigValidator = EmbeddingConfigValidator()
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun test(config: EmbeddingConfig, apiKey: String?): ConnectionTestResult {
        val validation = validator.validate(config)
        if (validation is ValidationResult.Invalid) {
            // 直接暴露首个字段错误，便于用户修正（如「向量维度必须为正整数」）。
            val first = validation.errors.firstOrNull()
            return ConnectionTestResult.InvalidResponse(
                "嵌入配置非法：${first?.message ?: "请修正后重试"}"
            )
        }
        if (EmbeddingConfigValidator.isBlank(config)) {
            return ConnectionTestResult.InvalidResponse("未配置在线嵌入模型，请先填写服务地址与模型标识")
        }
        val url = config.baseUrl.trim().trimEnd('/') + "/embeddings"
        val payload = json.encodeToString(
            EmbeddingProbeBody(model = config.modelId, input = listOf("ping"))
        )

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
        apiKey?.let { requestBuilder.header("Authorization", "Bearer $it") }

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
        response.code in 200..299 -> ConnectionTestResult.Success("embeddings")
        response.code == 401 || response.code == 403 -> ConnectionTestResult.Unauthorized
        else -> ConnectionTestResult.InvalidResponse("HTTP ${response.code}", "embeddings")
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 3_000L
        const val DEFAULT_READ_TIMEOUT_MS = 10_000L
        const val DEFAULT_TOTAL_TIMEOUT_MS = 10_000L
    }
}

@Serializable
private data class EmbeddingProbeBody(
    val model: String,
    val input: List<String>
)
