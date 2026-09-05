package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import androidx.annotation.RequiresApi
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.AsrErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionConfig
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEngine
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionError
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEvent
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrPublicConfig

/**
 * [SpeechRecognitionEngine] backed by the Android SpeechRecognizer
 * (IVI-IVAI-DSN-CR-006).
 *
 * - wraps a system Recognition Service (or API 31+ on-device recognizer when
 *   [onDevice] is true)
 * - hides main-thread calls, RecognitionListener callbacks and platform error
 *   codes; callers only see [SpeechRecognitionEvent]s
 * - maps Android error codes to stable IVAI-ASR-xxx codes
 * - stop / cancel / destroy are idempotent and always run on the main thread
 */
class AndroidSpeechRecognizerEngine(
    private val context: Context,
    private val onDevice: Boolean,
    private val publicConfig: AsrPublicConfig = AsrPublicConfig()
) : SpeechRecognitionEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val eventsChannel = Channel<SpeechRecognitionEvent>(Channel.BUFFERED)
    override fun events(): Flow<SpeechRecognitionEvent> = eventsChannel.receiveAsFlow()

    @Volatile
    private var recognizer: SpeechRecognizer? = null

    override suspend fun capability(): SpeechCapability =
        if (onDevice) SpeechCapability.ON_DEVICE else SpeechCapability.SYSTEM_SERVICE

    override fun start(config: SpeechRecognitionConfig): Result<Unit> = runCatching {
        runOnMain {
            ensureRecognizer()
            recognizer?.startListening(AndroidSpeechIntentFactory.create(config))
        }
    }

    override fun stop() {
        runOnMain { recognizer?.stopListening() }
    }

    override fun cancel() {
        runOnMain { recognizer?.cancel() }
    }

    override fun release() {
        runOnMain {
            recognizer?.destroy()
            recognizer = null
        }
        eventsChannel.close()
    }

    // ------------------------------------------------------------------ internals

    private fun ensureRecognizer() {
        if (recognizer != null) return
        val created = if (onDevice) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                throw IllegalStateException("on-device recognizer requires API 31+")
            }
            createOnDeviceRecognizer()
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        created.setRecognitionListener(listener)
        recognizer = created
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createOnDeviceRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) =
            emit(SpeechRecognitionEvent.Ready)

        override fun onBeginningOfSpeech() =
            emit(SpeechRecognitionEvent.Listening)

        override fun onEndOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.bestText()?.takeIf { it.isNotBlank() }
            if (text != null) emit(SpeechRecognitionEvent.PartialResult(text))
        }

        override fun onResults(results: Bundle?) {
            val text = results?.bestText()
            if (text.isNullOrBlank()) {
                emit(
                    SpeechRecognitionEvent.Error(
                        error(AsrErrorCode.EMPTY_RESULT, "未识别到有效语音")
                    )
                )
            } else {
                emit(SpeechRecognitionEvent.FinalResult(text))
            }
        }

        override fun onError(error: Int) = emit(SpeechRecognitionEvent.Error(mapError(error)))

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun Bundle.bestText(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun mapError(androidError: Int): SpeechRecognitionError = when (androidError) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            error(AsrErrorCode.PERMISSION_DENIED, "麦克风权限未授予")
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            error(AsrErrorCode.NETWORK_ERROR, "识别网络错误或超时")
        SpeechRecognizer.ERROR_NO_MATCH ->
            error(AsrErrorCode.NO_MATCH, "无法匹配语音，请重新说一次")
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            error(AsrErrorCode.NO_SPEECH, "未检测到语音")
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            error(AsrErrorCode.RECOGNIZER_BUSY, "识别服务忙，请稍后再试")
        SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_AUDIO,
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT ->
            error(AsrErrorCode.RECOGNIZER_INTERNAL, "识别器内部错误")
        else ->
            error(AsrErrorCode.RECOGNIZER_INTERNAL, "识别器错误（$androidError）")
    }

    private fun error(code: String, message: String) = SpeechRecognitionError(
        code = code,
        message = message,
        engineType = if (onDevice) "android-on-device" else "android-system",
        onDevice = onDevice
    )

    @Suppress("DELICATE_CALLS") // guarded by isClosedForSend; dropping events after release is fine
    private fun emit(event: SpeechRecognitionEvent) {
        if (!eventsChannel.isClosedForSend) eventsChannel.trySend(event)
    }

    /** Runs [block] on the main thread (SpeechRecognizer contract); no-op if already there. */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        mainHandler.post {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(MAIN_OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("主线程语音操作超时")
        }
        failure?.let { throw it }
    }

    private companion object {
        const val MAIN_OP_TIMEOUT_MS = 2_000L
    }
}
