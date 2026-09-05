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
 * Creates [AndroidSpeechRecognizerEngine]s according to the configured provider
 * and the device capability (IVI-IVAI-DSN-CR-006 capability selection):
 *
 * - ANDROID_ON_DEVICE → API 31+ on-device recognizer; when unavailable and the
 *   fallback policy allows, the standard system recognizer is used instead.
 * - ANDROID_SYSTEM → standard Recognition Service when available.
 * - HTTP_COMPATIBLE / VENDOR → reserved for a future CR; returns null (never
 *   enters a fake listening state).
 *
 * Returns null when the requested provider is unavailable / not implemented —
 * the caller must refuse the voice session and keep text input working.
 *
 * The internal constructor takes the capability checker and engine creator as
 * injectables so the selection + fallback logic is unit-testable without a
 * device; the public [Context] constructor wires the real Android APIs.
 */
class AndroidSpeechRecognitionEngineFactory internal constructor(
    private val checker: AndroidSpeechCapabilityChecker,
    private val engineCreator: (Boolean, AsrPublicConfig) -> SpeechRecognitionEngine
) : SpeechRecognitionEngineFactory {

    constructor(context: Context) : this(
        checker = AndroidSpeechCapabilityChecker(context),
        engineCreator = { onDevice, publicConfig ->
            AndroidSpeechRecognizerEngine(context, onDevice, publicConfig)
        }
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

            // ON_DEVICE_TO_REMOTE / SYSTEM_TO_REMOTE and direct remote providers
            // are implemented by future CRs; until then no engine is created and
            // the voice entry falls back to text (TEXT_ONLY semantics).
            AsrProviderType.HTTP_COMPATIBLE, AsrProviderType.VENDOR -> null
        }
    }

    /** Device capability for the UI (disable the voice entry when unavailable). */
    fun capability(): SpeechCapability = checker.capability()
}
