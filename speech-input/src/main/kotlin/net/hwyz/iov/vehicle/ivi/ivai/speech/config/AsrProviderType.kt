package net.hwyz.iov.vehicle.ivi.ivai.speech.config

/**
 * ASR provider type (IVI-IVAI-DSN-CR-006). Android providers need no remote
 * address / API key; HTTP_COMPATIBLE / VENDOR are configured now but their
 * engine implementations arrive in a future CR (reserved via the unified
 * [net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEngine]).
 */
enum class AsrProviderType {
    ANDROID_ON_DEVICE,
    ANDROID_SYSTEM,
    HTTP_COMPATIBLE,
    VENDOR
}

/**
 * Degradation policy when the preferred engine is unavailable (IVI-IVAI-DSN-CR-006).
 *
 * TEXT_ONLY (default) never touches the network — a failed / unavailable engine
 * falls back to text input only, so audio is never silently uploaded. The
 * *_TO_REMOTE policies are reserved for future remote engines; switching to a
 * remote recognizer must always be explicit in the UI, never inferred from
 * EXTRA_PREFER_OFFLINE.
 */
enum class AsrFallbackPolicy {
    TEXT_ONLY,
    ON_DEVICE_TO_SYSTEM,
    ON_DEVICE_TO_REMOTE,
    SYSTEM_TO_REMOTE
}
