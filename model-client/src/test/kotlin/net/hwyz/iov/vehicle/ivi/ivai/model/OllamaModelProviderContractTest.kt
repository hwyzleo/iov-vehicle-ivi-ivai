package net.hwyz.iov.vehicle.ivi.ivai.model

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Contract tests for [OllamaModelProvider]: request/response shape and error conversion.
 */
class OllamaModelProviderContractTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OllamaModelProvider

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OllamaModelProvider(
            OllamaConfig(baseUrl = server.url("/").toString())
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun outerResponse(contentJson: String, code: Int = 200): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setBody(
                """
                {"model":"qwen3.5:4b","created_at":"2026-09-03T00:00:00Z",
                 "message":{"role":"assistant","content":$contentJson},
                 "done":true,"done_reason":"stop"}
                """.trimIndent()
            )

    @Test
    fun `parses outer envelope and second-level content json`() = runBlocking {
        val inner = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power_on","arguments":{"position":"driver"}}]}"""
        server.enqueue(outerResponse(JsonPrimitive(inner).toString()))

        val response = provider.generate(request(listOf(ChatMessage("user", "打开空调"))))

        assertEquals("req-1", response.requestId)
        assertEquals("qwen3.5:4b", response.model)
        assertEquals("stop", response.finishReason)
        val obj = response.contentJson!!.jsonObject
        assertEquals("LOCAL_TOOL", obj["route"]!!.jsonPrimitive.content)
        val intent = obj["intents"]!!.jsonArray[0].jsonObject
        assertEquals("climate.power_on", intent["toolId"]!!.jsonPrimitive.content)
        assertTrue(response.latencyMs >= 0)
    }

    @Test
    fun `sends chat payload with model messages stream=false and options`() = runBlocking {
        val inner = """{"route":"REJECT","intents":[]}"""
        server.enqueue(outerResponse(JsonPrimitive(inner).toString()))

        provider.generate(request(listOf(ChatMessage("user", "你好"))))

        val recorded = server.takeRequest()
        assertEquals("/api/chat", recorded.path)
        assertEquals("req-1", recorded.getHeader("X-IVAI-Request-Id"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("qwen3.5:4b", body["model"]!!.jsonPrimitive.content)
        assertEquals(false, body["stream"]!!.jsonPrimitive.content.toBoolean())
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("你好", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        val options = body["options"]!!.jsonObject
        assertEquals(0.0, options["temperature"]!!.jsonPrimitive.double, 0.0001)
        assertEquals(512, options["num_predict"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `markdown code fence around content is stripped before parsing`() = runBlocking {
        val inner = """```json
{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power_on"}]}
```"""
        server.enqueue(outerResponse(JsonPrimitive(inner).toString()))

        val response = provider.generate(request(listOf(ChatMessage("user", "打开空调"))))

        val obj = response.contentJson!!.jsonObject
        assertEquals("LOCAL_TOOL", obj["route"]!!.jsonPrimitive.content)
    }

    @Test
    fun `plain text content parses to json literal and is left for agent schema check`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"model":"qwen3.5:4b","message":{"role":"assistant","content":"我无法处理"},"done":true}"""
            )
        )
        val response = provider.generate(request(listOf(ChatMessage("user", "hi"))))
        assertEquals("我无法处理", response.content)
        assertTrue(response.contentJson is JsonPrimitive)
    }

    @Test
    fun `malformed outer response maps to RESPONSE_PARSE_ERROR`() = runBlocking {
        server.enqueue(MockResponse().setBody("not-json-at-all"))
        val e = try {
            provider.generate(request(emptyList()))
            null
        } catch (ex: ModelClientException) {
            ex
        }
        assertNotNull(e)
        assertEquals(ModelErrorKind.RESPONSE_PARSE_ERROR, e!!.kind)
    }

    @Test
    fun `message content that is not json maps to RESPONSE_PARSE_ERROR`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"model":"qwen3.5:4b","message":{"role":"assistant","content":"{broken json"},"done":true}"""
            )
        )
        val e = try {
            provider.generate(request(emptyList()))
            null
        } catch (ex: ModelClientException) {
            ex
        }
        assertNotNull(e)
        assertEquals(ModelErrorKind.RESPONSE_PARSE_ERROR, e!!.kind)
    }

    @Test
    fun `http 500 maps to HTTP_ERROR with code`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))
        val e = try {
            provider.generate(request(emptyList()))
            null
        } catch (ex: ModelClientException) {
            ex
        }
        assertNotNull(e)
        assertEquals(ModelErrorKind.HTTP_ERROR, e!!.kind)
        assertEquals(500, e.httpCode)
    }

    @Test
    fun `slow response beyond timeout maps to TIMEOUT`() = runBlocking {
        server.enqueue(
            outerResponse(JsonPrimitive("""{"route":"REJECT","intents":[]}""").toString())
                .setBodyDelay(2, TimeUnit.SECONDS)
        )
        val e = try {
            provider.generate(
                ModelRequest(
                    requestId = "req-t",
                    model = "qwen3.5:4b",
                    messages = emptyList(),
                    timeoutMs = 200
                )
            )
            null
        } catch (ex: ModelClientException) {
            ex
        }
        assertNotNull(e)
        assertEquals(ModelErrorKind.TIMEOUT, e!!.kind)
    }

    @Test
    fun `connection refused maps to NETWORK_UNAVAILABLE`() = runBlocking {
        val offline = OllamaModelProvider(OllamaConfig(baseUrl = "http://127.0.0.1:1"))
        val e = try {
            offline.generate(request(listOf(ChatMessage("user", "hi"))))
            null
        } catch (ex: ModelClientException) {
            ex
        }
        assertNotNull(e)
        assertEquals(ModelErrorKind.NETWORK_UNAVAILABLE, e!!.kind)
    }

    private fun request(messages: List<ChatMessage>) =
        ModelRequest(
            requestId = "req-1",
            model = "qwen3.5:4b",
            messages = messages
        )
}
