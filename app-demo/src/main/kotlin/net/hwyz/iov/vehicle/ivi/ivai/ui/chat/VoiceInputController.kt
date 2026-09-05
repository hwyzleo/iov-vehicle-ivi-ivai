package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.AsrErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionConfig
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEngine
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionError
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEvent
import net.hwyz.iov.vehicle.ivi.ivai.speech.observability.SpeechRecognitionMetrics
import net.hwyz.iov.vehicle.ivi.ivai.speech.observability.SpeechRecognitionMetricsRecorder

/**
 * Push-to-talk controller (IVI-IVAI-DSN-CR-006).
 *
 * - serializes all state transitions with a [Mutex]: at most one active session
 * - each press produces a unique voiceSessionId; stale / duplicate callbacks
 *   are dropped (IVAI-ASR-009) and each session submits at most one request
 * - PartialResult only updates the temporary UI; FinalResult (non-empty) is the
 *   single trigger for [VoiceInputDeps.submitText]
 * - hold timeout + finalize timeout map to IVAI-ASR-005; recognizer errors map
 *   to their IVAI-ASR code and recover to Idle (never auto-restart listening)
 * - records [SpeechRecognitionMetrics] as an input-stage metric, never merged
 *   into the Agent's endToEndMs
 */
class VoiceInputController(
    private val scope: CoroutineScope,
    private val deps: VoiceInputDeps,
    private val metricsRecorder: SpeechRecognitionMetricsRecorder = SpeechRecognitionMetricsRecorder {},
    private val idleAfterErrorMs: Long = DEFAULT_IDLE_AFTER_ERROR_MS,
    private val idleAfterCancelMs: Long = DEFAULT_IDLE_AFTER_CANCEL_MS,
    private val finalizeTimeoutMs: Long = DEFAULT_FINALIZE_TIMEOUT_MS,
    private val maxHoldMs: Long = DEFAULT_MAX_HOLD_MS,
    private val nowNs: () -> Long = { System.nanoTime() }
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    private var engine: SpeechRecognitionEngine? = null
    private var eventsJob: Job? = null
    private var sessionJob: Job? = null
    private val submittedSessions = mutableSetOf<String>()
    private var currentVoiceSessionId: String? = null

    // Diagnostics / metrics.
    private var engineType = "unknown"
    private var onDevice = false
    private var prepareStartNs = 0L
    private var listeningStartNs = 0L
    private var finalizeStartNs = 0L

    fun startVoiceInput() {
        scope.launch { startVoiceInputInternal() }
    }

    fun stopVoiceInput() {
        scope.launch { stopVoiceInputInternal() }
    }

    fun cancelVoiceInput() {
        scope.launch { cancelVoiceInputInternal() }
    }

    /** Releases the recognizer and mic (page destroy / permission lost / focus lost). */
    fun release() {
        scope.launch {
            mutex.withLock {
                if (_state.value is VoiceInputState.Idle) return@withLock
                teardown()
                _state.value = VoiceInputState.Idle
            }
        }
    }

    // ------------------------------------------------------------------ start

    private suspend fun startVoiceInputInternal() {
        mutex.withLock {
            // Restart allowed from Idle (and transient Failed so the user can retry).
            val resting = _state.value is VoiceInputState.Idle ||
                _state.value is VoiceInputState.Failed
            if (!resting) return

            _state.value = VoiceInputState.Checking
            val session = runCatching { deps.createEngine() }.getOrNull()
            if (session == null) {
                failAndReset(
                    VoiceInputError(
                        AsrErrorCode.NO_RECOGNITION_SERVICE,
                        "语音识别当前不可用，请检查权限与识别服务，或改用文本输入"
                    )
                )
                return
            }

            engine = session.engine
            engineType = when (session.engine.capability()) {
                SpeechCapability.ON_DEVICE -> "android-on-device"
                SpeechCapability.REMOTE -> "http-compatible"
                SpeechCapability.SYSTEM_SERVICE -> "android-system"
                SpeechCapability.UNAVAILABLE -> "unavailable"
            }
            onDevice = session.engine.capability() == SpeechCapability.ON_DEVICE

            val voiceSessionId = UUID.randomUUID().toString()
            currentVoiceSessionId = voiceSessionId
            _state.value = VoiceInputState.Preparing
            prepareStartNs = nowNs()

            eventsJob = scope.launch {
                session.engine.events().collect { event -> handleEvent(voiceSessionId, event) }
            }

            val started = session.engine.start(
                SpeechRecognitionConfig(
                    languageTag = session.languageTag,
                    partialResults = true,
                    preferOffline = session.preferOffline,
                    sessionId = voiceSessionId
                )
            )
            if (started.isFailure) {
                failAndReset(
                    VoiceInputError(AsrErrorCode.RECOGNIZER_INTERNAL, "无法启动语音识别，请重试")
                )
            }
        }
    }

    // ------------------------------------------------------------------ stop / cancel

    private suspend fun stopVoiceInputInternal() {
        mutex.withLock {
            val current = _state.value
            if (current !is VoiceInputState.Listening && current !is VoiceInputState.Preparing) return
            val eng = engine ?: return
            sessionJob?.cancel()
            sessionJob = null
            _state.value = VoiceInputState.Finalizing
            finalizeStartNs = nowNs()
            eng.stop()
            sessionJob = scope.launch {
                delay(finalizeTimeoutMs)
                mutex.withLock {
                    if (_state.value is VoiceInputState.Finalizing) {
                        failAndReset(VoiceInputError(AsrErrorCode.RECOGNITION_TIMEOUT, "识别超时，请重试"))
                    }
                }
            }
        }
    }

    private suspend fun cancelVoiceInputInternal() {
        mutex.withLock {
            if (_state.value is VoiceInputState.Idle || _state.value is VoiceInputState.Cancelled) return
            sessionJob?.cancel()
            sessionJob = null
            engine?.cancel()
            teardown()
            _state.value = VoiceInputState.Cancelled
            scope.launch {
                delay(idleAfterCancelMs)
                mutex.withLock {
                    if (_state.value is VoiceInputState.Cancelled) _state.value = VoiceInputState.Idle
                }
            }
        }
    }

    // ------------------------------------------------------------------ events

    private suspend fun handleEvent(voiceSessionId: String, event: SpeechRecognitionEvent) {
        mutex.withLock {
            // Stale / expired callback from an earlier or replaced session.
            if (voiceSessionId != currentVoiceSessionId) return

            when (event) {
                SpeechRecognitionEvent.Ready -> Unit // PREPARING continues until Listening
                SpeechRecognitionEvent.Listening -> {
                    listeningStartNs = nowNs()
                    _state.value = VoiceInputState.Listening()
                    startHoldTimeout(voiceSessionId)
                }
                is SpeechRecognitionEvent.PartialResult -> {
                    val current = _state.value
                    if (current is VoiceInputState.Listening) {
                        _state.value = current.copy(partialText = event.text)
                    }
                }
                is SpeechRecognitionEvent.FinalResult -> onFinalResult(voiceSessionId, event.text)
                is SpeechRecognitionEvent.Error -> onEngineError(event.error)
            }
        }
    }

    private fun startHoldTimeout(voiceSessionId: String) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            delay(maxHoldMs)
            mutex.withLock {
                if (currentVoiceSessionId == voiceSessionId &&
                    _state.value is VoiceInputState.Listening
                ) {
                    failAndReset(VoiceInputError(AsrErrorCode.RECOGNITION_TIMEOUT, "按住时间过长，识别已结束"))
                }
            }
        }
    }

    private suspend fun onFinalResult(voiceSessionId: String, rawText: String) {
        sessionJob?.cancel()
        sessionJob = null
        _state.value = VoiceInputState.Finalizing
        finalizeStartNs = nowNs()

        val text = rawText.trim()
        if (text.isEmpty()) {
            recordMetrics(voiceSessionId, errorCode = AsrErrorCode.EMPTY_RESULT, resultLength = 0)
            teardown()
            _state.value = VoiceInputState.Idle
            return
        }

        // At most one request per voiceSessionId — drop duplicate callbacks.
        if (!submittedSessions.add(voiceSessionId)) {
            recordMetrics(voiceSessionId, errorCode = AsrErrorCode.STALE_SESSION, resultLength = text.length)
            teardown()
            _state.value = VoiceInputState.Idle
            return
        }

        recordMetrics(voiceSessionId, resultLength = text.length)
        deps.submitText(voiceSessionId, text)
        teardown()
        _state.value = VoiceInputState.Idle
    }

    private suspend fun onEngineError(error: SpeechRecognitionError) {
        sessionJob?.cancel()
        sessionJob = null
        recordMetrics(currentVoiceSessionId, errorCode = error.code)
        failAndReset(VoiceInputError(error.code, userMessage(error)))
    }

    private suspend fun failAndReset(error: VoiceInputError) {
        teardown()
        _state.value = VoiceInputState.Failed(error)
        scope.launch {
            delay(idleAfterErrorMs)
            mutex.withLock {
                if (_state.value is VoiceInputState.Failed) _state.value = VoiceInputState.Idle
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Cancels the event/session jobs and releases the recognizer. Never suspends. */
    private fun teardown() {
        sessionJob?.cancel()
        sessionJob = null
        eventsJob?.cancel()
        eventsJob = null
        engine?.release()
        engine = null
        currentVoiceSessionId = null
        listeningStartNs = 0L
        finalizeStartNs = 0L
    }

    private fun recordMetrics(voiceSessionId: String?, errorCode: String? = null, resultLength: Int? = null) {
        if (voiceSessionId == null) return
        val now = nowNs()
        val finalizeStart = finalizeStartNs.takeIf { it > 0 }
        val listeningStart = listeningStartNs.takeIf { it > 0 }
        metricsRecorder.record(
            SpeechRecognitionMetrics(
                voiceSessionId = voiceSessionId,
                engineType = engineType,
                onDevice = onDevice,
                prepareMs = msBetween(prepareStartNs, listeningStart ?: finalizeStart ?: now),
                listeningMs = msBetween(listeningStart, finalizeStart ?: now),
                finalizingMs = msBetween(finalizeStart, now),
                resultLength = resultLength,
                errorCode = errorCode
            )
        )
    }

    private fun msBetween(fromNs: Long?, toNs: Long): Long? =
        if (fromNs != null && fromNs > 0) ((toNs - fromNs) / 1_000_000).coerceAtLeast(0) else null

    private fun userMessage(error: SpeechRecognitionError): String = when (error.code) {
        AsrErrorCode.PERMISSION_DENIED -> "麦克风权限未授予，请在设置中开启"
        AsrErrorCode.NO_RECOGNITION_SERVICE -> "语音识别不可用，请改用文本输入"
        AsrErrorCode.NO_SPEECH -> "未检测到语音，请重试"
        AsrErrorCode.NO_MATCH -> "没有听清，请重新说一次"
        AsrErrorCode.RECOGNITION_TIMEOUT -> "识别超时，请重试"
        AsrErrorCode.NETWORK_ERROR -> "网络错误，请检查网络或切换识别引擎"
        AsrErrorCode.RECOGNIZER_BUSY -> "识别服务忙，请稍后再试"
        AsrErrorCode.RECOGNIZER_INTERNAL -> "识别器出错，请重试"
        AsrErrorCode.REMOTE_UNAUTHORIZED -> "远程识别服务鉴权失败，请检查 API Key"
        AsrErrorCode.REMOTE_INVALID_RESPONSE -> "远程识别服务响应异常，请稍后重试"
        AsrErrorCode.REMOTE_SERVICE_ERROR -> "远程识别服务暂不可用，请稍后重试"
        else -> error.message.ifEmpty { "语音识别失败" }
    }

    private companion object {
        const val DEFAULT_IDLE_AFTER_ERROR_MS = 1_500L
        const val DEFAULT_IDLE_AFTER_CANCEL_MS = 800L
        const val DEFAULT_FINALIZE_TIMEOUT_MS = 5_000L
        const val DEFAULT_MAX_HOLD_MS = 15_000L
    }
}
