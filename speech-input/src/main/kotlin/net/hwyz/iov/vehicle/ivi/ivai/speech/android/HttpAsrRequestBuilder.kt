package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretValue
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Builds the HTTP_COMPATIBLE ASR upload request (IVI-IVAI-DSN-CR-007).
 *
 * Contract v1:
 *  - POST to the full configured [baseUrl] (may already include
 *    `/v1/audio/transcriptions`; the path is never re-appended).
 *  - multipart/form-data with `file=@voice.wav; type=audio/wav` (16 kHz /
 *    16-bit / mono WAV), optional `model`, optional `language`.
 *  - `Authorization: Bearer <apiKey>` only when a key is configured; the value
 *    is consumed via [SecretValue.use] and must never enter logs / toString().
 *
 * The audio part is named `file`, matching the OpenAI Whisper / SiliconFlow
 * `POST /audio/transcriptions` contract (both require the upload field to be
 * `file`); `model` is required by those providers, `language` is optional.
 */
class HttpAsrRequestBuilder(
    private val baseUrl: String,
    private val modelName: String? = null,
    private val languageTag: String? = null,
    private val apiKey: SecretValue? = null
) {

    /**
     * @throws IllegalArgumentException when [baseUrl] is not a legal http(s) URL.
     */
    fun build(audioWav: ByteArray): Request {
        val url = baseUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("ASR 服务地址非法: $baseUrl")
        if (url.scheme != "http" && url.scheme != "https") {
            throw IllegalArgumentException("仅支持 http/https 协议")
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                AUDIO_FILENAME,
                audioWav.toRequestBody("audio/wav".toMediaType())
            )
            .apply {
                modelName?.takeIf { it.isNotBlank() }?.let { addFormDataPart("model", it) }
                languageTag?.takeIf { it.isNotBlank() }?.let { addFormDataPart("language", it) }
            }
            .build()

        return Request.Builder()
            .url(url)
            .post(multipart)
            .apply {
                apiKey?.use { key ->
                    header("Authorization", "Bearer $key")
                }
            }
            .build()
    }

    companion object {
        const val AUDIO_FILENAME = "voice.wav"
    }
}
