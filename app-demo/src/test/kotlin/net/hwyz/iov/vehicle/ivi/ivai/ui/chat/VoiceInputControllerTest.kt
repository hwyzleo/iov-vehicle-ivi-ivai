package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.AsrErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionError
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEvent
import net.hwyz.iov.vehicle.ivi.ivai.speech.observability.SpeechRecognitionMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Push-to-talk state machine (IVI-IVAI-DSN-CR-006): single session per press,
 * PartialResult never submits, FinalResult (non-empty) submits exactly once,
 * cancel / empty / stale / errors recover to Idle without extra submissions,
 * and timeout paths map to IVAI-ASR-005.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputControllerTest {

    private class FakeDeps(
        var engine: StubSpeechEngine? = null,
        var submitReturns: Boolean = true
    ) : VoiceInputDeps {
        var createCalls = 0
        val submitted = mutableListOf<Pair<String, String>>() // voiceSessionId → text
        var lastRejected: Boolean? = null

        override suspend fun createEngine(): VoiceEngineSession? {
            createCalls++
            val e = engine ?: return null
            return VoiceEngineSession(
                engine = e,
                languageTag = "zh-CN",
                preferOffline = true,
                recognitionTimeoutMs = 30_000L
            )
        }

        override suspend fun submitText(voiceSessionId: String, text: String): Boolean {
            submitted += voiceSessionId to text
            lastRejected = !submitReturns
            return submitReturns
        }
    }

    private fun controller(
        scope: kotlinx.coroutines.test.TestScope,
        deps: FakeDeps,
        metrics: MutableList<SpeechRecognitionMetrics> = mutableListOf()
    ): VoiceInputController = VoiceInputController(
        scope = scope,
        deps = deps,
        metricsRecorder = { metrics += it },
        idleAfterErrorMs = 1_500,
        idleAfterCancelMs = 800,
        finalizeTimeoutMs = 5_000,
        maxHoldMs = 15_000
    )

    @Test
    fun `重复按下只创建一个识别会话`() = runTest {
        val engine = StubSpeechEngine()
        val deps = FakeDeps(engine = engine)
        val controller = controller(this, deps)

        controller.startVoiceInput()
        runCurrent()
        // Second press while the first session is active must be ignored.
        controller.startVoiceInput()
        runCurrent()

        assertEquals(1, deps.createCalls)
        assertEquals(1, engine.startCalls)
        controller.release()
        runCurrent()
    }

    @Test
    fun `PartialResult 不提交，FinalResult 提交一次并去除首尾空白`() = runTest {
        val engine = StubSpeechEngine()
        val deps = FakeDeps(engine = engine)
        val controller = controller(this, deps)

        controller.startVoiceInput()
        runCurrent()
        engine.emit(SpeechRecognitionEvent.Listening)
        runCurrent()
        engine.emit(SpeechRecognitionEvent.PartialResult("开"))
        runCurrent()
        assertEquals(VoiceInputState.Listening("开"), controller.state.value)

        controller.stopVoiceInput()
        runCurrent()
        assertEquals(VoiceInputState.Finalizing, controller.state.value)
        assertEquals(1, engine.stopCalls)

        engine.emit(SpeechRecognitionEvent.FinalResult("  打开空调  "))
        runCurrent()

        assertEquals(1, deps.submitted.size)
        assertEquals("打开空调", deps.submitted.first().second)
        assertEquals(VoiceInputState.Idle, controller.state.value)
        assertNull(controller.state.value as? VoiceInputState.Listening)
    }

    @Test
    fun `空白最终结果不提交并恢复空闲`() = runTest {
        val engine = StubSpeechEngine()
        val deps = FakeDeps(engine = engine)
        val controller = controller(this, deps)

        controller.startVoiceInput()
        runCurrent()
        engine.emit(SpeechRecognitionEvent.Listening)
        runCurrent()
        controller.stopVoiceInput()
        runCurrent()
        engine.emit(SpeechRecognitionEvent.FinalResult("    "))
        runCurrent()

        assertTrue(deps.submitted.isEmpty())
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun `取消手势不提交并释放引擎`() = runTest {
        val engine = StubSpeechEngine()
        val deps = FakeDeps(engine = engine)
        val controller = controller(this, deps)

        controller.startVoiceInput()
        runCurrent()
        engine.emit(SpeechRecognitionEvent.Listening)
        runCurrent()

        controller.cancelVoiceInput()
        runCurrent()

        assertEquals(1, engine.cancelCalls)
        assertTrue(deps.submitted.isEmpty())
        assertEquals(VoiceInputState.Cancelled, controller.state.value)
        advanceTimeBy(900)
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun `重复 FinalResult 只提交一次`() = runTest {
        val engine = StubSpeechEngine()
        val deps = FakeDeps(engine = engine)
        val controller = controller(this, deps)

        controller.startVoiceInput()
        runCurrent()
        engine.emit(SpeechRecognitionEvent.Listening)
        runCurrent()
        controller.stopVoiceInput()
        runCurrent()
        engine.emit(SpeechRecognitionEvent.FinalResult("打开空调"))
        runCurrent()
        // A duplicate / stale final for the same session must be dropped.
        engine.emit(SpeechRecognitionEvent.FinalResult("打开空调"))
        runCurrent()

        assertEquals(1, deps.submitted.size)
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun `服务不可用时不进入伪聆听并恢复空闲`() = runTest {
        val deps = FakeDeps(engine = null)
        val controller = controller(this, deps)

        controller.startVoiceInput()
        runCurrent()

        val failed = controller.state.value as? VoiceInputState.Failed
        assertEquals(AsrErrorCode.NO_RECOGNITION_SERVICE, failed?.error?.code)
        advanceTimeBy(1_600)
        assertEquals(VoiceInputState.Idle, controller.state.value)
        assertEquals(0, deps.submitted.size)
    }

    @Test
    fun `识别错误映射错误码并恢复空闲`() = runTest {
        val engine = StubSpeechEngine()
        val deps = FakeDeps(engine = engine)
        val controller = controller(this, deps)

        controller.startVoiceInput()
        runCurrent()
        engine.emit(
            SpeechRecognitionEvent.Error(
                SpeechRecognitionError(AsrErrorCode.RECOGNIZER_BUSY, "busy")
            )
        )
        runCurrent()

        val failed = controller.state.value as? VoiceInputState.Failed
        assertEquals(AsrErrorCode.RECOGNIZER_BUSY, failed?.error?.code)
        assertTrue(deps.submitted.isEmpty())
        advanceTimeBy(1_600)
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun `按住超时映射 IVAI-ASR-005`() = runTest {
        val engine = StubSpeechEngine()
        val deps = FakeDeps(engine = engine)
        val controller = controller(this, deps)

        controller.startVoiceInput()
        runCurrent()
        engine.emit(SpeechRecognitionEvent.Listening)
        runCurrent()

        advanceTimeBy(17_000) // maxHoldMs(15s) fires → Failed; +2s settles to Idle
        assertTrue(deps.submitted.isEmpty())
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun `Finalizing 超时映射 IVAI-ASR-005`() = runTest {
        val engine = StubSpeechEngine()
        val deps = FakeDeps(engine = engine)
        val controller = controller(this, deps)

        controller.startVoiceInput()
        runCurrent()
        engine.emit(SpeechRecognitionEvent.Listening)
        runCurrent()
        controller.stopVoiceInput()
        runCurrent()
        // No FinalResult ever arrives.
        advanceTimeBy(7_000) // finalize timeout(5s) → Failed; +2s settles to Idle
        assertTrue(deps.submitted.isEmpty())
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun `提交被拒绝（Agent 忙碌）时静默恢复空闲`() = runTest {
        val engine = StubSpeechEngine()
        val deps = FakeDeps(engine = engine, submitReturns = false)
        val controller = controller(this, deps)

        controller.startVoiceInput()
        runCurrent()
        engine.emit(SpeechRecognitionEvent.Listening)
        runCurrent()
        controller.stopVoiceInput()
        runCurrent()
        engine.emit(SpeechRecognitionEvent.FinalResult("打开空调"))
        runCurrent()

        // submitText was invoked once but rejected; the session still resets cleanly.
        assertEquals(1, deps.submitted.size)
        assertEquals(true, deps.lastRejected)
        assertEquals(VoiceInputState.Idle, controller.state.value)
    }

    @Test
    fun `成功提交记录独立 ASR 指标`() = runTest {
        val engine = StubSpeechEngine()
        val deps = FakeDeps(engine = engine)
        val metrics = mutableListOf<SpeechRecognitionMetrics>()
        val controller = controller(this, deps, metrics)

        controller.startVoiceInput()
        runCurrent()
        engine.emit(SpeechRecognitionEvent.Listening)
        runCurrent()
        controller.stopVoiceInput()
        runCurrent()
        engine.emit(SpeechRecognitionEvent.FinalResult("打开空调"))
        runCurrent()

        assertEquals(1, metrics.size)
        assertEquals("打开空调".length, metrics.first().resultLength)
        assertEquals(false, metrics.first().onDevice)
        assertNull(metrics.first().errorCode)
        advanceUntilIdle()
    }
}
