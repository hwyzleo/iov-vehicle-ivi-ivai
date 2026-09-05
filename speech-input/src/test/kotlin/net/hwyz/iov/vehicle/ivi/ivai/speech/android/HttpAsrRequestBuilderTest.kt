package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretValue
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HTTP_COMPATIBLE multipart request construction (IVI-IVAI-DSN-CR-007):
 * POST to the full baseUrl (path never re-appended), audio/model/language
 * form parts, and the Authorization header only when a key is configured.
 */
class HttpAsrRequestBuilderTest {

    private val wav = ByteArray(64) { it.toByte() }

    private fun multipart(request: okhttp3.Request): MultipartBody =
        request.body as MultipartBody

    private fun partDisposition(part: MultipartBody.Part): String =
        part.headers?.get("Content-Disposition") ?: ""

    private fun partBody(part: MultipartBody.Part): ByteArray {
        val buffer = Buffer()
        part.body.writeTo(buffer)
        return buffer.readByteArray()
    }

    @Test
    fun `posts to the exact configured base url without re-appending the path`() {
        val request = HttpAsrRequestBuilder(
            baseUrl = "https://asr.example.com/v1/audio/transcriptions",
            modelName = "whisper-1",
            languageTag = "zh-CN"
        ).build(wav)

        assertEquals("POST", request.method)
        assertEquals(
            "https://asr.example.com/v1/audio/transcriptions",
            request.url.toString()
        )
    }

    @Test
    fun `includes audio model and language as multipart fields`() {
        val request = HttpAsrRequestBuilder(
            baseUrl = "https://asr.example.com/transcriptions",
            modelName = "whisper-1",
            languageTag = "zh-CN"
        ).build(wav)

        val body = multipart(request)
        assertEquals(MultipartBody.FORM, body.type)

        val audio = body.parts[0]
        assertTrue(partDisposition(audio).contains("name=\"file\""))
        assertTrue(partDisposition(audio).contains("filename=\"voice.wav\""))
        assertEquals("audio/wav", audio.body.contentType()?.toString())
        assertTrue(partBody(audio).contentEquals(wav))

        val model = body.parts[1]
        assertTrue(partDisposition(model).contains("name=\"model\""))
        assertEquals("whisper-1", partBody(model).toString(Charsets.UTF_8))

        val language = body.parts[2]
        assertTrue(partDisposition(language).contains("name=\"language\""))
        assertEquals("zh-CN", partBody(language).toString(Charsets.UTF_8))
    }

    @Test
    fun `model and language are optional`() {
        val request = HttpAsrRequestBuilder(
            baseUrl = "https://asr.example.com/transcriptions"
        ).build(wav)

        val body = multipart(request)
        assertEquals(1, body.parts.size)
        assertTrue(partDisposition(body.parts[0]).contains("name=\"file\""))
    }

    @Test
    fun `blank model is omitted while a non blank language is kept`() {
        val request = HttpAsrRequestBuilder(
            baseUrl = "https://asr.example.com/transcriptions",
            modelName = "   ",
            languageTag = "en-US"
        ).build(wav)

        val body = multipart(request)
        assertEquals(2, body.parts.size)
        assertTrue(partDisposition(body.parts[0]).contains("name=\"file\""))
        assertTrue(partDisposition(body.parts[1]).contains("name=\"language\""))
    }

    @Test
    fun `bearer authorization is added when a key is configured`() {
        val request = HttpAsrRequestBuilder(
            baseUrl = "https://asr.example.com/transcriptions",
            apiKey = SecretValue.of("sk-secret")
        ).build(wav)

        assertEquals("Bearer sk-secret", request.header("Authorization"))
    }

    @Test
    fun `no authorization header without a key`() {
        val request = HttpAsrRequestBuilder(
            baseUrl = "https://asr.example.com/transcriptions"
        ).build(wav)

        assertNull(request.header("Authorization"))
    }

    @Test
    fun `rejects illegal or non http urls`() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpAsrRequestBuilder(baseUrl = "not-a-url").build(wav)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpAsrRequestBuilder(baseUrl = "file:///etc/passwd").build(wav)
        }
    }

    @Test
    fun `secret is masked in toString of the builder context`() {
        val builder = HttpAsrRequestBuilder(
            baseUrl = "https://asr.example.com/transcriptions",
            apiKey = SecretValue.of("sk-secret")
        )
        assertFalse(builder.toString().contains("sk-secret"))
    }
}
