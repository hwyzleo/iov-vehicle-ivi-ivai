package net.hwyz.iov.vehicle.ivi.ivai.speech.api

import kotlinx.coroutines.flow.Flow

/**
 * Device speech-recognition capability detected at runtime (IVI-IVAI-DSN-CR-006).
 *
 * - [ON_DEVICE] means an on-device recognizer is available (API 31+
 *   `isOnDeviceRecognitionAvailable`). Only this capability is treated as a
 *   local/offline recognizer.
 * - [SYSTEM_SERVICE] means only a standard Recognition Service exists; whether
 *   it is online is decided by the actual service — it must never be advertised
 *   as deterministically offline.
 * - [UNAVAILABLE] means no usable Recognition Service / recognizer on this
 *   device; the voice entry must not enter a fake listening state.
 */
enum class SpeechCapability {
    ON_DEVICE,
    SYSTEM_SERVICE,
    UNAVAILABLE
}

/**
 * Unified speech recognition engine contract (IVI-IVAI-DSN-CR-006).
 *
 * Implementations MUST hide Android main-thread calls, Listener callbacks,
 * platform error codes and resource release inside the module — the Chatbot /
 * VoiceInputController only observes [SpeechRecognitionEvent]s and drives the
 * session via [start] / [stop] / [cancel] / [release].
 */
interface SpeechRecognitionEngine {

    /** Detected recognition capability of this engine on the current device. */
    suspend fun capability(): SpeechCapability

    /** Stream of recognition events for the current (or most recent) session. */
    fun events(): Flow<SpeechRecognitionEvent>

    /** Starts a listening session. Fails when the mic is unavailable or busy. */
    fun start(config: SpeechRecognitionConfig): Result<Unit>

    /** Stops listening and waits for the final result (ACTION_UP). */
    fun stop()

    /** Cancels the session; no final result is delivered (gesture cancel). */
    fun cancel()

    /** Releases platform resources (SpeechRecognizer.destroy()). Idempotent. */
    fun release()
}
