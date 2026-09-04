package net.hwyz.iov.vehicle.ivi.ivai.model.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.model.ChatMessage
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelClientException
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelErrorKind
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * OpenAI compatible streaming contract (SSE `data:` frames): delta content is
 * forwarded in order, [DONE] terminates the stream, time-to-first-token is
 * client-observed and empty streams map to IVAI-MODEL-003 (INVALID_RESPONSE).
 */
class OpenAiCompatibleModelProviderStreamingTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAiCompatibleModelProvider

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAiCompatibleModelProvider(
            OpenAICompatibleConfig(baseUrl = server.url("/").toString())
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun sse(delta: String) =
        "data: {\"id\":\"c\",\"choices\":[{\"delta\":{\"content\":${Json.encodeToString(delta)}}}]}\n\n"

    private fun sseDone() = "data: {\"id\":\"c\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"

    @Test
    fun `streams sse deltas and reports ttft`() = runBlocking {
        val full = """{"route":"LOCAL_TOOL","intents":[]}"""
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                buildString {
                    append(sse(full.take(12)))
                    append(sse(full.drop(12)))
                    append(sseDone())
                    append("data: [DONE]\n\n")
                }
            )
        )

        val deltas = mutableListOf<String>()
        val response = provider.generateStreaming(
            ModelRequest("req-oai-s1", "qwen3.5:4b", listOf(ChatMessage("user", "hi")), timeoutMs = 5_000)
        ) { delta -> deltas += delta }

        assertEquals(listOf(full.take(12), full.drop(12)), deltas)
        assertEquals(full, response.content)
        assertEquals("stop", response.finishReason)
        assertNotNull(response.timeToFirstTokenMs)
        assertTrue(response.timeToFirstTokenMs!! >= 0)
        assertEquals(false, response.network?.connectionReused)
    }

    @Test
    fun `sends stream true in the payload`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                buildString {
                    append(sse("x"))
                    append(sseDone())
                    append("data: [DONE]\n\n")
                }
            )
        )
        provider.generateStreaming(
            ModelRequest("req-oai-s2", "qwen3.5:4b", listOf(ChatMessage("user", "hi")), timeoutMs = 5_000)
        ) { }

        assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun `empty stream maps to invalid response`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                buildString {
                    append(sseDone())
                    append("data: [DONE]\n\n")
                }
            )
        )
        val e = runCatching {
            provider.generateStreaming(
                ModelRequest("req-oai-s3", "qwen3.5:4b", listOf(ChatMessage("user", "hi")), timeoutMs = 5_000)
            ) { }
        }.exceptionOrNull()
        assertTrue(e is ModelClientException)
        assertEquals(ModelErrorKind.INVALID_RESPONSE, (e as ModelClientException).kind)
    }
}
