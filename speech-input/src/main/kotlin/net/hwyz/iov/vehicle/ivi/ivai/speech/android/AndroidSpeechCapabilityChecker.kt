package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability

/**
 * Runtime speech capability detection (IVI-IVAI-DSN-CR-006).
 *
 * The platform checks are injectable lambdas so the selection logic is
 * unit-testable without a device; the primary constructor wires the real
 * Android APIs (API 31+ for on-device recognition).
 */
class AndroidSpeechCapabilityChecker(
    private val hasPermission: () -> Boolean,
    private val onDeviceAvailable: () -> Boolean,
    private val systemAvailable: () -> Boolean
) {

    constructor(context: Context) : this(
        hasPermission = {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        },
        onDeviceAvailable = {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        },
        systemAvailable = {
            SpeechRecognizer.isRecognitionAvailable(context)
        }
    )

    /** True only when the mic permission is granted. */
    fun hasRecordAudioPermission(): Boolean = hasPermission()

    /** On-device recognizer available (API 31+ and capability check). */
    fun isOnDeviceAvailable(): Boolean = onDeviceAvailable()

    /** Standard Recognition Service available (may still be network-backed). */
    fun isSystemAvailable(): Boolean = systemAvailable()

    /**
     * Capability selection from the design:
     * permission → API31+ on-device → system service → unavailable.
     */
    fun capability(): SpeechCapability = when {
        !hasPermission() -> SpeechCapability.UNAVAILABLE
        isOnDeviceAvailable() -> SpeechCapability.ON_DEVICE
        isSystemAvailable() -> SpeechCapability.SYSTEM_SERVICE
        else -> SpeechCapability.UNAVAILABLE
    }
}
