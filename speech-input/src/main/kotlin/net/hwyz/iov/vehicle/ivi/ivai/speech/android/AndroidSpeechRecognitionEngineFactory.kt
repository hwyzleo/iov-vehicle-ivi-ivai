package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import android.content.Context
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEngine
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEngineFactory
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrFallbackPolicy
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrProviderType
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrPublicConfig
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrRuntimeConfig

/**
 * Creates speech engines according to the configured provider
 * (IVI-IVAI-DSN-CR-006 capability selection + IVI-IVAI-DSN-CR-007 remote):
 *
 * - ANDROID_ON_DEVICE → API 31+ on-device recognizer; when unavailable and the
 *   fallback policy allows, the standard system recognizer is used instead.
 * - ANDROID_SYSTEM → standard Recognition Service when available.
 * - HTTP_COMPATIBLE → [HttpCompatibleSpeechEngine] (non-streaming WAV upload),
 *   built from the immutable [AsrRuntimeConfig] snapshot.
 * - VENDOR → reserved for a future CR; returns null (never enters a fake
 *   listening state).
 *
 * Returns null when the requested provider is unavailable / not implemented —
 * the caller must refuse the voice session and keep text input working.
 *
 * The internal constructor takes the capability checker, the Android engine
 * creator and the remote engine creator as injectables so the selection logic
 * is unit-testable without a device; the public [Context] constructor wires the
 * real Android APIs.
 */
class AndroidSpeechRecognitionEngineFactory internal constructor(
    private val checker: AndroidSpeechCapabilityChecker,
    private val engineCreator: (Boolean, AsrPublicConfig) -> SpeechRecognitionEngine,
    private val httpEngineCreator: (AsrRuntimeConfig) -> SpeechRecognitionEngine? = { null }
) : SpeechRecognitionEngineFactory {

    constructor(context: Context) : this(
        checker = AndroidSpeechCapabilityChecker(context),
        engineCreator = { onDevice, publicConfig ->
            AndroidSpeechRecognizerEngine(context, onDevice, publicConfig)
        },
        httpEngineCreator = { config -> createHttpEngine(config) }
    )

    override fun create(config: AsrRuntimeConfig): SpeechRecognitionEngine? {
        if (!checker.hasRecordAudioPermission()) return null
        val p = config.public
        return when (p.providerType) {
            AsrProviderType.ANDROID_ON_DEVICE -> {
                when {
                    checker.isOnDeviceAvailable() -> engineCreator(true, p)
                    p.fallbackPolicy == AsrFallbackPolicy.ON_DEVICE_TO_SYSTEM &&
                        checker.isSystemAvailable() -> engineCreator(false, p)
                    else -> null
                }
            }

            AsrProviderType.ANDROID_SYSTEM -> {
                when {
                    checker.isSystemAvailable() -> engineCreator(false, p)
                    else -> null
                }
            }

            AsrProviderType.HTTP_COMPATIBLE -> httpEngineCreator(config)

            // VENDOR private engines arrive in a future CR; until then no engine
            // is created and the voice entry falls back to text.
            AsrProviderType.VENDOR -> null
        }
    }

    /** Device capability for the UI (disable the voice entry when unavailable). */
    fun capability(): SpeechCapability = checker.capability()

    /**
     * Builds the remote engine from the immutable snapshot. The recorder, URL
     * builder and client factory all capture this session's configVersion; a
     * config save only affects later sessions.
     */
    private companion object {
        fun createHttpEngine(config: AsrRuntimeConfig): SpeechRecognitionEngine? {
            val p = config.public
            val baseUrl = p.baseUrl ?: return null
            return HttpCompatibleSpeechEngine(
                recorder = AndroidAudioRecorder(
                    sourceFactory = { AndroidAudioRecordSource() }
                ),
                requestBuilder = HttpAsrRequestBuilder(
                    baseUrl = baseUrl,
                    modelName = p.modelName,
                    languageTag = p.languageTag,
                    apiKey = config.apiKey
                ),
                responseParser = HttpAsrResponseParser(),
                clientFactory = HttpAsrClientFactory(p.connectTimeoutMs, p.recognitionTimeoutMs),
                recognitionTimeoutMs = p.recognitionTimeoutMs
            )
        }
    }
}
