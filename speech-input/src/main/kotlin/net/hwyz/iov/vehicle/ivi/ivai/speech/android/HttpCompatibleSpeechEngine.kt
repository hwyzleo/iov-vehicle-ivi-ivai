package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.AsrErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionConfig
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEngine
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionError
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEvent
import net.hwyz.iov.vehicle.ivi.ivai.speech.observability.SpeechRecognitionMetrics
import net.hwyz.iov.vehicle.ivi.ivai.speech.observability.SpeechRecognitionMetricsRecorder
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * HTTP_COMPATIBLE remote ASR engine (IVI-IVAI-DSN-CR-007).
 *
 * Non-streaming v1 flow: ACTION_DOWN → [start] begins AudioRecord capture and
 * emits Ready/Listening; ACTION_UP → [stop] takes the PCM, wraps it in WAV and
 * uploads it once as multipart/form-data to the configured baseUrl, then emits
 * a single FinalResult (or a stable Error). [cancel] aborts without a final
 * result; [release] frees the recorder, the coroutines and the in-flight Call.
 *
 * - session token: only the current voiceSessionId may publish events; stale
 *   recording threads / OkHttp callbacks are dropped
 * - single session → at most one FinalResult (no automatic network retry;
 *   a user-initiated retry starts a brand-new voiceSessionId)
 * - timeout budget: connectTimeoutMs (client) + recognitionTimeoutMs covering
 *   upload start → full response read; timeouts map to IVAI-ASR-005, DNS /
 *   connection failures to IVAI-ASR-006
 * - diagnostics via [SpeechRecognitionMetrics] with engineType = "http-compatible",
 *   onDevice = false (never audio / keys / Authorization / full response body)
 */
class HttpCompatibleSpeechEngine(
    private val recorder: AndroidAudioRecorder,
    private val requestBuilder: HttpAsrRequestBuilder,
    private val responseParser: HttpAsrResponseParser,
    private val clientFactory: HttpAsrClientFactory,
    private val recognitionTimeoutMs: Long = DEFAULT_RECOGNITION_TIMEOUT_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val metricsRecorder: SpeechRecognitionMetricsRecorder? = null,
    private val nowNs: () -> Long = { System.nanoTime() }
) : SpeechRecognitionEngine {

    private val eventsChannel = Channel<SpeechRecognitionEvent>(Channel.BUFFERED)
    override fun events(): Flow<SpeechRecognitionEvent> = eventsChannel.receiveAsFlow()

    private val client: OkHttpClient by lazy { clientFactory.create() }

    @Volatile
    private var currentSessionId: String? = null
    private var uploadJob: Job? = null
    private var uploadCall: Call? = null

    // Diagnostics for the current session.
    private var wavEncodeMs: Long? = null
    private var uploadMs: Long? = null
    private var responseReadMs: Long? = null
    private var httpStatus: Int? = null
    private var finalizeStartNs = 0L

    override suspend fun capability(): SpeechCapability = SpeechCapability.REMOTE

    override fun start(config: SpeechRecognitionConfig): Result<Unit> {
        currentSessionId = config.sessionId
        finalizeStartNs = 0L
        wavEncodeMs = null
        uploadMs = null
        responseReadMs = null
        httpStatus = null

        val started = recorder.start(config.sessionId)
        if (started.isFailure) {
            val t = started.exceptionOrNull() ?: IllegalStateException("录音启动失败")
            val code = if (t is SecurityException) {
                AsrErrorCode.PERMISSION_DENIED
            } else {
                AsrErrorCode.RECOGNIZER_BUSY
            }
            val error = SpeechRecognitionError(
                code = code,
                message = if (code == AsrErrorCode.PERMISSION_DENIED) {
                    "麦克风权限未授予"
                } else {
                    "录音设备不可用或已被占用"
                },
                engineType = "http-compatible",
                onDevice = false,
                cause = t
            )
            recordMetrics(config.sessionId, errorCode = error.code)
            emit(SpeechRecognitionEvent.Error(error))
            return Result.success(Unit) // failure already surfaced via the event stream
        }

        emit(SpeechRecognitionEvent.Ready)
        emit(SpeechRecognitionEvent.Listening)
        return Result.success(Unit)
    }

    override fun stop() {
        val sessionId = currentSessionId ?: return
        uploadJob?.cancel()
        uploadJob = scope.launch {
            finalizeStartNs = nowNs()

            val pcm = recorder.stop().getOrElse { t ->
                emitError(AsrErrorCode.RECOGNIZER_INTERNAL, "录音停止失败: ${t.message}", sessionId)
                return@launch
            }

            val encodeStart = nowNs()
            val wav = runCatching { WavEncoder.encode(pcm) }.getOrElse { t ->
                emitError(AsrErrorCode.RECOGNIZER_INTERNAL, "WAV 编码失败: ${t.message}", sessionId)
                return@launch
            }
            wavEncodeMs = msSince(encodeStart)

            upload(sessionId, wav)
        }
    }

    override fun cancel() {
        uploadJob?.cancel()
        uploadJob = null
        uploadCall?.cancel()
        uploadCall = null
        recorder.cancel()
        currentSessionId = null
        recordMetrics(null) // nothing to record without a session id
    }

    override fun release() {
        uploadJob?.cancel()
        uploadJob = null
        uploadCall?.cancel()
        uploadCall = null
        recorder.release()
        currentSessionId = null
        scope.cancel()
        eventsChannel.close()
    }

    // ------------------------------------------------------------------ upload

    private suspend fun upload(sessionId: String, wav: ByteArray) {
        val request = runCatching { requestBuilder.build(wav) }.getOrElse { t ->
            emitError(AsrErrorCode.RECOGNIZER_INTERNAL, "上传请求构造失败: ${t.message}", sessionId)
            return
        }

        val uploadStart = nowNs()
        val result = runCatching {
            withTimeout(recognitionTimeoutMs) {
                suspendCancellableCoroutine<HttpAsrParseResult> { cont ->
                    val call = client.newCall(request)
                    uploadCall = call
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (cont.isCancelled) return
                            cont.resume(networkFailure(e))
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val readStart = nowNs()
                            try {
                                val parsed = responseParser.parse(response)
                                responseReadMs = msSince(readStart)
                                if (cont.isCancelled) return
                                cont.resume(parsed)
                            } finally {
                                response.close()
                            }
                        }
                    })
                }
            }
        }
        uploadMs = msSince(uploadStart)

        val parsed: HttpAsrParseResult = when {
            result.isFailure -> {
                val t = result.exceptionOrNull()!!
                // TimeoutCancellationException extends CancellationException — it must
                // be mapped to IVAI-ASR-005, NOT swallowed as a session cancel.
                if (t is TimeoutCancellationException || t is SocketTimeoutException) {
                    HttpAsrParseResult.Failure(
                        error = SpeechRecognitionError(
                            code = AsrErrorCode.RECOGNITION_TIMEOUT,
                            message = "识别超时",
                            engineType = "http-compatible",
                            onDevice = false,
                            cause = t
                        ),
                        httpStatus = null
                    )
                } else if (t is CancellationException) {
                    return // session cancelled / released — drop silently
                } else {
                    HttpAsrParseResult.Failure(
                        error = SpeechRecognitionError(
                            code = AsrErrorCode.NETWORK_ERROR,
                            message = "识别网络错误: ${t.message}",
                            engineType = "http-compatible",
                            onDevice = false,
                            cause = t
                        ),
                        httpStatus = null
                    )
                }
            }
            else -> result.getOrThrow()
        }

        when (parsed) {
            is HttpAsrParseResult.Success -> {
                httpStatus = parsed.httpStatus
                recordMetrics(
                    sessionId,
                    resultLength = parsed.text.length,
                    httpStatus = parsed.httpStatus
                )
                emit(SpeechRecognitionEvent.FinalResult(parsed.text))
            }
            is HttpAsrParseResult.Failure -> {
                httpStatus = parsed.httpStatus
                emitError(parsed.error.code, parsed.error.message, sessionId, parsed.httpStatus)
            }
        }
    }

    private fun networkFailure(e: IOException): HttpAsrParseResult {
        val code = if (e is SocketTimeoutException) {
            AsrErrorCode.RECOGNITION_TIMEOUT
        } else {
            AsrErrorCode.NETWORK_ERROR
        }
        return HttpAsrParseResult.Failure(
            error = SpeechRecognitionError(
                code = code,
                message = if (code == AsrErrorCode.RECOGNITION_TIMEOUT) "识别超时" else "识别网络错误",
                engineType = "http-compatible",
                onDevice = false,
                cause = e
            ),
            httpStatus = null
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun emitError(code: String, message: String, sessionId: String, status: Int? = null) {
        recordMetrics(sessionId, errorCode = code, httpStatus = status)
        emit(
            SpeechRecognitionEvent.Error(
                SpeechRecognitionError(
                    code = code,
                    message = message,
                    engineType = "http-compatible",
                    onDevice = false
                )
            )
        )
    }

    private fun recordMetrics(
        voiceSessionId: String?,
        errorCode: String? = null,
        resultLength: Int? = null,
        httpStatus: Int? = null
    ) {
        if (voiceSessionId == null) return
        val finalizeStart = finalizeStartNs.takeIf { it > 0 }
        metricsRecorder?.record(
            SpeechRecognitionMetrics(
                voiceSessionId = voiceSessionId,
                engineType = "http-compatible",
                onDevice = false,
                wavEncodeMs = wavEncodeMs,
                uploadMs = uploadMs,
                responseReadMs = responseReadMs,
                finalizingMs = msSince(finalizeStart),
                resultLength = resultLength,
                httpStatus = httpStatus,
                errorCode = errorCode
            )
        )
    }

    private fun msSince(fromNs: Long?): Long? =
        if (fromNs != null && fromNs > 0) ((nowNs() - fromNs) / 1_000_000).coerceAtLeast(0) else null

    private fun emit(event: SpeechRecognitionEvent) {
        if (!eventsChannel.isClosedForSend) eventsChannel.trySend(event)
    }

    companion object {
        const val DEFAULT_RECOGNITION_TIMEOUT_MS = 30_000L
    }
}
