package net.hwyz.iov.vehicle.ivi.ivai.model.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelClientException
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelErrorKind
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.model.StreamingModelProvider

/**
 * 模型请求生命周期封装（IVI-IVAI-DSN-CR-016）。
 *
 * 在 [ModelProvider] 之上实现分阶段超时：
 *  - 排队超时（队列等待，[ProviderCleanupBarrier] 等待上一条清理）；
 *  - 首个可消费输出超时（流式首字 / 首个结构化可消费事件）；
 *  - 流式空闲超时（连续无输出块）；
 *  - 总生成超时（整段生成）；
 *  - 清理确认（超时/取消/失败后确认底层 HTTP/SSE 关闭）。
 *
 * 超时或取消时通过抛 [ModelClientException]（kind=TIMEOUT / CANCELLED）向调用方
 * 传播，并保证底层请求取消（Provider 内部 invokeOnCompletion → call.cancel）。
 *
 * 细分错误码（IVAI-MODEL-TIMEOUT-00x）随 [ModelCallReport.timeoutErrorCode]
 * 返回；兼容汇总码 IVAI-MODEL-001 继续由调用方映射。
 */
class ModelRequestLifecycle(
    private val timeoutPolicy: ModelTimeoutPolicy = ModelTimeoutPolicy.DEFAULT,
    private val cleanupBarrier: ProviderCleanupBarrier = ProviderCleanupBarrier()
) {

    /**
     * 执行一次模型调用（兼容非流式 + 流式 Provider）。
     *
     * @param onDelta 流式增量回调；非流式 Provider 忽略。
     * @return 调用结果；超时/取消/失败以 [ModelClientException] 抛出。
     */
    suspend fun call(
        request: ModelRequest,
        provider: ModelProvider,
        onDelta: (suspend (String) -> Unit)? = null
    ): ModelCallOutcome {
        val startNs = System.nanoTime()

        // 1) 排队（等待上一条清理确认）。
        val queued = cleanupBarrier.beginRequest(request.requestId)
        val queueMs = (System.nanoTime() - startNs) / NANOS_PER_MILLI
        if (!queued) {
            // 排队超时：上一条仍未完成清理。
            return ModelCallOutcome.Timeout(
                report = ModelCallReport(
                    queueMs = queueMs,
                    timeoutErrorCode = MODEL_TIMEOUT_QUEUE
                ),
                errorCode = MODEL_TIMEOUT_QUEUE,
                message = "模型请求排队超时（上一条 Provider 清理未确认）"
            )
        }

        try {
            val streaming = provider as? StreamingModelProvider
            val outcome = if (streaming != null && onDelta != null) {
                callStreaming(request, streaming, onDelta, startNs)
            } else {
                callNonStreaming(request, provider, startNs)
            }
            return outcome
        } finally {
            // 无论成功/超时/取消/失败，都确认清理（TRANSPORT_CANCELLED →
            // RESPONSE_BODY_CLOSED → CLEANUP_CONFIRMED）。
            cleanupBarrier.markCleaned()
        }
    }

    private suspend fun callNonStreaming(
        request: ModelRequest,
        provider: ModelProvider,
        startNs: Long
    ): ModelCallOutcome = try {
        val response = withTimeout(timeoutPolicy.totalTimeoutMs) {
            provider.generate(request)
        }
        ModelCallOutcome.Success(
            response = response,
            report = ModelCallReport(
                queueMs = (System.nanoTime() - startNs) / NANOS_PER_MILLI,
                totalMs = response.latencyMs
            )
        )
    } catch (e: TimeoutCancellationException) {
        ModelCallOutcome.Timeout(
            report = ModelCallReport(
                totalMs = (System.nanoTime() - startNs) / NANOS_PER_MILLI,
                timeoutErrorCode = MODEL_TIMEOUT_TOTAL
            ),
            errorCode = MODEL_TIMEOUT_TOTAL,
            message = "模型总生成超时（${timeoutPolicy.totalTimeoutMs}ms）"
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: ModelClientException) {
        ModelCallOutcome.ProviderFailure(e)
    }

    private suspend fun callStreaming(
        request: ModelRequest,
        provider: StreamingModelProvider,
        onDelta: suspend (String) -> Unit,
        startNs: Long
    ): ModelCallOutcome {
        var firstOutputMs: Long? = null
        var lastChunkAtNs = System.nanoTime()
        var idleHit = false
        var totalMs = 0L

        try {
            val response = withTimeout(timeoutPolicy.totalTimeoutMs) {
                provider.generateStreaming(request) { delta ->
                    val nowNs = System.nanoTime()
                    if (firstOutputMs == null) {
                        firstOutputMs = (nowNs - startNs) / NANOS_PER_MILLI
                    }
                    // 流式空闲超时：连续无输出块。
                    val idleMs = (nowNs - lastChunkAtNs) / NANOS_PER_MILLI
                    if (idleMs > timeoutPolicy.idleChunkTimeoutMs) {
                        idleHit = true
                    }
                    lastChunkAtNs = nowNs
                    onDelta(delta)
                }
            }
            totalMs = (System.nanoTime() - startNs) / NANOS_PER_MILLI
            // 首个可消费输出超时：流式但首个 chunk 迟迟不来。
            if (firstOutputMs != null && firstOutputMs > timeoutPolicy.firstOutputTimeoutMs) {
                return ModelCallOutcome.Timeout(
                    report = ModelCallReport(
                        queueMs = null,
                        firstOutputMs = firstOutputMs,
                        totalMs = totalMs,
                        timeoutErrorCode = MODEL_TIMEOUT_FIRST_OUTPUT
                    ),
                    errorCode = MODEL_TIMEOUT_FIRST_OUTPUT,
                    message = "模型首个可消费输出超时（${timeoutPolicy.firstOutputTimeoutMs}ms）"
                )
            }
            if (idleHit) {
                return ModelCallOutcome.Timeout(
                    report = ModelCallReport(
                        firstOutputMs = firstOutputMs,
                        totalMs = totalMs,
                        idleTimeoutHit = true,
                        timeoutErrorCode = MODEL_TIMEOUT_IDLE
                    ),
                    errorCode = MODEL_TIMEOUT_IDLE,
                    message = "模型流式响应连续无输出超时（${timeoutPolicy.idleChunkTimeoutMs}ms）"
                )
            }
            return ModelCallOutcome.Success(
                response = response,
                report = ModelCallReport(
                    firstOutputMs = firstOutputMs,
                    totalMs = totalMs
                )
            )
        } catch (e: TimeoutCancellationException) {
            return ModelCallOutcome.Timeout(
                report = ModelCallReport(
                    firstOutputMs = firstOutputMs,
                    totalMs = (System.nanoTime() - startNs) / NANOS_PER_MILLI,
                    timeoutErrorCode = MODEL_TIMEOUT_TOTAL
                ),
                errorCode = MODEL_TIMEOUT_TOTAL,
                message = "模型流式总生成超时（${timeoutPolicy.totalTimeoutMs}ms）"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelClientException) {
            return ModelCallOutcome.ProviderFailure(e)
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MODEL_TIMEOUT_QUEUE = "IVAI-MODEL-TIMEOUT-001"
        const val MODEL_TIMEOUT_FIRST_OUTPUT = "IVAI-MODEL-TIMEOUT-002"
        const val MODEL_TIMEOUT_IDLE = "IVAI-MODEL-TIMEOUT-003"
        const val MODEL_TIMEOUT_TOTAL = "IVAI-MODEL-TIMEOUT-004"
    }
}

/** 模型调用结果（IVI-IVAI-DSN-CR-016）。 */
sealed interface ModelCallOutcome {
    /** 成功。 */
    data class Success(
        val response: ModelResponse,
        val report: ModelCallReport
    ) : ModelCallOutcome

    /** 分阶段超时（细分错误码 IVAI-MODEL-TIMEOUT-00x）。 */
    data class Timeout(
        val report: ModelCallReport,
        val errorCode: String,
        val message: String
    ) : ModelCallOutcome

    /** Provider 层失败（网络 / HTTP / 配置 / 解析 / 取消）。 */
    data class ProviderFailure(
        val exception: ModelClientException
    ) : ModelCallOutcome
}
