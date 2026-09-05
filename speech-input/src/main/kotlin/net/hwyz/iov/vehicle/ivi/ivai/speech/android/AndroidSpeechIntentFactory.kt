package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import android.content.Intent
import android.speech.RecognizerIntent
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionConfig

/**
 * Builds the RecognitionIntent extras for a session (IVI-IVAI-DSN-CR-006).
 *
 * - EXTRA_LANGUAGE defaults to zh-CN (can be overridden by the vehicle
 *   language later).
 * - EXTRA_PARTIAL_RESULTS=true is only for the temporary UI preview.
 * - EXTRA_PREFER_OFFLINE=true is a preference, never an offline guarantee.
 */
object AndroidSpeechIntentFactory {

    fun create(config: SpeechRecognitionConfig): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, config.partialResults)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, config.preferOffline)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
}
