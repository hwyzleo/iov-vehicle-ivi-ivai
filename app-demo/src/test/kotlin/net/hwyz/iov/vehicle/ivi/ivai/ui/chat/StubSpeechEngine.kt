package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionConfig
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEngine
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEvent

/**
 * Test [SpeechRecognitionEngine] with a controllable event flow
 * (IVI-IVAI-DSN-CR-006). Used by VoiceInputController / ChatViewModel tests.
 */
class StubSpeechEngine(
    private val capability: SpeechCapability = SpeechCapability.SYSTEM_SERVICE
) : SpeechRecognitionEngine {

    private val eventsFlow = MutableSharedFlow<SpeechRecognitionEvent>(extraBufferCapacity = 100)

    var startCalls = 0
    var stopCalls = 0
    var cancelCalls = 0
    var releaseCalls = 0
    var lastConfig: SpeechRecognitionConfig? = null

    override suspend fun capability(): SpeechCapability = capability

    override fun events(): Flow<SpeechRecognitionEvent> = eventsFlow

    override fun start(config: SpeechRecognitionConfig): Result<Unit> {
        startCalls++
        lastConfig = config
        return Result.success(Unit)
    }

    override fun stop() {
        stopCalls++
    }

    override fun cancel() {
        cancelCalls++
    }

    override fun release() {
        releaseCalls++
    }

    fun emit(event: SpeechRecognitionEvent) {
        eventsFlow.tryEmit(event)
    }
}
