package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability

/**
 * Real [VoiceInputGateway] backed by AgentService's shared ASR config
 * repository and engine factory (IVI-IVAI-DSN-CR-006).
 */
class ServiceVoiceInputGateway(private val service: AgentService) : VoiceInputGateway {

    override suspend fun createVoiceSession(): VoiceEngineSession? {
        val snapshot = runCatching { service.asrConfigRepository.loadSnapshot() }.getOrNull()
            ?: return null
        val engine = service.asrEngineFactory.create(snapshot) ?: return null
        val p = snapshot.public
        return VoiceEngineSession(
            engine = engine,
            languageTag = p.languageTag,
            preferOffline = p.preferOffline,
            recognitionTimeoutMs = p.recognitionTimeoutMs
        )
    }

    override fun capability(): SpeechCapability = service.asrCapability()
}
