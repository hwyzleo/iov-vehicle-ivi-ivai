package net.hwyz.iov.vehicle.ivi.ivai.model.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.hwyz.iov.vehicle.ivi.ivai.model.ChatMessage
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelClientException
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelErrorKind
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiChatRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiChatResponse
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiChoice
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiResponseMessage
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai.OpenAiTiming
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Contract tests for [OpenAiCompatibleModelProvider] (IVI-IVAI-DSN-CR-004):
 * non-streaming Chat Completions request shape, Authorization header, unified
 * response conversion, endpoint path joining and error mapping.
 */
class OpenAiCompatibleModelProviderContractTest {

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

    private fun chatResponse(
        content: String,
        timing: OpenAiTiming? = null,
        code: Int = 200
    ): MockResponse {
        val choice = OpenAiChoice(
            index = 0,
            message = OpenAiResponseMessage(role = "assistant", content = content),
            finishReason = "stop"
        )
        val body = OpenAiChatResponse(
            id = "chatcmpl-1",
            model = "qwen3.5:4b",
            choices = listOf(choice),
            timing = timing
        )
        return MockResponse()
            .setResponseCode(code)
            .setBody(Json.encodeToString(OpenAiChatResponse.serializer(), body))
    }

    private fun request(messages: List<ChatMessage> = listOf(ChatMessage("user", "打开空调"))) =
        ModelRequest(requestId = "req-1", model = "qwen3.5:4b", messages = messages)

    @Test
    fun `posts non-streaming chat completions with model messages and auth`() = runBlocking {
        val inner = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power_on"}]}"""
        server.enqueue(chatResponse(inner))

        val withKey = OpenAiCompatibleModelProvider(
            OpenAICompatibleConfig(
                baseUrl = server.url("/").toString(),
                apiKey = "sk-test-secret"
            )
        )
        withKey.generate(request())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test-secret", recorded.getHeader("Authorization"))
        assertEquals("req-1", recorded.getHeader("X-IVAI-Request-Id"))

        val rawBody = recorded.body.readUtf8()
        // enable_thinking 必须显式下发（SiliconFlow 推理模型默认可能开启思考）。
        assertTrue(
            rawBody.contains("\"enable_thinking\":false"),
            "请求体必须显式关闭思考，实际: $rawBody"
        )
        val body = Json.decodeFromString(OpenAiChatRequest.serializer(), rawBody)
        assertEquals("qwen3.5:4b", body.model)
        assertEquals(false, body.stream)
        assertEquals(0.0, body.temperature)
        assertEquals(false, body.enableThinking)
        assertEquals("user", body.messages.first().role)
        assertEquals("打开空调", body.messages.first().content)
    }

    @Test
    fun `converts choices content to unified response with provider timing`() = runBlocking {
        val inner = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power_on","arguments":{"position":"driver"}}]}"""
        server.enqueue(
            chatResponse(
                inner,
                timing = OpenAiTiming(promptEvalMs = 10, generationMs = 300, totalMs = 310)
            )
        )

        val response = provider.generate(request())

        assertEquals("req-1", response.requestId)
        assertEquals("qwen3.5:4b", response.model)
        assertEquals("stop", response.finishReason)
        val obj = response.contentJson!!.jsonObject
        assertEquals("LOCAL_TOOL", obj["route"]!!.jsonPrimitive.content)
        assertEquals("climate.power_on", obj["intents"]!!.jsonArray[0].jsonObject["toolId"]!!.jsonPrimitive.content)

        val compute = response.providerCompute
        assertNotNull(compute)
        assertEquals(10L, compute!!.promptEvaluationMs)
        assertEquals(300L, compute.generationMs)
        assertEquals("openai_compatible", compute.source)
        assertTrue(response.latencyMs >= 0)
    }

    @Test
    fun `captures network sub-metrics for a single request`() = runBlocking {
        val inner = """{"route":"REJECT","intents":[]}"""
        server.enqueue(chatResponse(inner))

        val response = provider.generate(request())

        val network = response.network
        assertNotNull(network)
        assertEquals(false, network!!.connectionReused)
        assertNotNull(network.timeToFirstByteMs)
        assertNotNull(network.responseReadMs)
        // Local HTTP: no TLS events → null, never 0.
        assertNull(network.tlsMs)
    }

    @Test
    fun `joins endpoint path without duplicating v1`() = runBlocking {
        // baseUrl already contains /v1 → the default endpoint must not repeat it.
        server.enqueue(chatResponse("""{"route":"REJECT","intents":[]}"""))
        provider = OpenAiCompatibleModelProvider(
            OpenAICompatibleConfig(
                baseUrl = server.url("/v1").toString(),
                endpointPath = "/v1/chat/completions"
            )
        )

        provider.generate(request())
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
    }

    @Test
    fun `uses custom endpoint path when configured`() = runBlocking {
        server.enqueue(chatResponse("""{"route":"REJECT","intents":[]}"""))
        provider = OpenAiCompatibleModelProvider(
            OpenAICompatibleConfig(
                baseUrl = server.url("/").toString(),
                endpointPath = "/custom/chat"
            )
        )

        provider.generate(request())
        assertEquals("/custom/chat", server.takeRequest().path)
    }

    @Test
    fun `401 maps to http error`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}""")
        )
        val e = runCatching { provider.generate(request()) }.exceptionOrNull()
        assertTrue(e is ModelClientException)
        assertEquals(ModelErrorKind.HTTP_ERROR, (e as ModelClientException).kind)
        assertEquals(401, e.httpCode)
    }

    @Test
    fun `empty choices maps to invalid response`() = runBlocking {
        val body = OpenAiChatResponse(id = "x", choices = emptyList())
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(Json.encodeToString(OpenAiChatResponse.serializer(), body))
        )
        val e = runCatching { provider.generate(request()) }.exceptionOrNull()
        assertTrue(e is ModelClientException)
        assertEquals(ModelErrorKind.INVALID_RESPONSE, (e as ModelClientException).kind)
    }

    @Test
    fun `missing message content maps to invalid response`() = runBlocking {
        val body = OpenAiChatResponse(
            id = "x",
            choices = listOf(OpenAiChoice(index = 0, message = OpenAiResponseMessage(role = "assistant", content = null)))
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(Json.encodeToString(OpenAiChatResponse.serializer(), body))
        )
        val e = runCatching { provider.generate(request()) }.exceptionOrNull()
        assertTrue(e is ModelClientException)
        assertEquals(ModelErrorKind.INVALID_RESPONSE, (e as ModelClientException).kind)
    }

    @Test
    fun `content that is not valid json maps to response parse error after repair attempt`() = runBlocking {
        // 主请求非 JSON → 触发修复重试；修复后仍非 JSON → 002。
        server.enqueue(chatResponse("this is { not json"))
        server.enqueue(chatResponse("still not json either"))
        val e = runCatching { provider.generate(request()) }.exceptionOrNull()
        assertTrue(e is ModelClientException)
        assertEquals(ModelErrorKind.RESPONSE_PARSE_ERROR, (e as ModelClientException).kind)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `non json content is repaired by a follow up model request`() = runBlocking {
        val goodJson = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.temperature_increase"}]}"""
        // 主请求返回自然语言（非 JSON），修复请求返回合法 JSON。
        server.enqueue(chatResponse("好的，我帮您把温度调高一点。"))
        server.enqueue(chatResponse(goodJson))

        val response = provider.generate(request())

        assertEquals(
            "LOCAL_TOOL",
            response.contentJson!!.jsonObject["route"]!!.jsonPrimitive.content
        )
        assertEquals(2, server.requestCount)
        // 第一个请求是主请求；第二个是修复请求（messages 含 system 修复指令）。
        server.takeRequest()
        val repaired = server.takeRequest()
        assertEquals("POST", repaired.method)
        val repairedBody = Json.decodeFromString(OpenAiChatRequest.serializer(), repaired.body.readUtf8())
        assertTrue(repairedBody.messages.any { it.role == "system" })
    }

    @Test
    fun `malformed outer body maps to response parse error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))
        val e = runCatching { provider.generate(request()) }.exceptionOrNull()
        assertTrue(e is ModelClientException)
        assertEquals(ModelErrorKind.RESPONSE_PARSE_ERROR, (e as ModelClientException).kind)
    }
}
