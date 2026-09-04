package net.hwyz.iov.vehicle.ivi.ivai.model

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigException
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigSnapshotProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelRuntimeConfig
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretValue
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Runtime snapshot semantics of [OllamaModelProvider] (IVI-IVAI-DSN-CR-003 /
 * IVAI-REQ-025): one immutable snapshot per request, live config switches
 * between requests, Authorization header only when a key is set, and config
 * failure refusing the request.
 */
class OllamaModelProviderConfigTest {

    private class FakeSnapshotProvider(var snapshot: ModelRuntimeConfig) : ModelConfigSnapshotProvider {
        var calls = 0
            private set
        override suspend fun loadSnapshot(): ModelRuntimeConfig {
            calls++
            return snapshot
        }
    }

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun outerResponse(contentJson: String): MockResponse =
        MockResponse().setResponseCode(200).setBody(
            """
            {"model":"qwen3.5:4b","created_at":"2026-09-03T00:00:00Z",
             "message":{"role":"assistant","content":$contentJson},
             "done":true,"done_reason":"stop"}
            """.trimIndent()
        )

    private fun request() = ModelRequest(
        requestId = "req-1",
        model = "qwen3.5:4b",
        messages = listOf(ChatMessage("user", "打开空调"))
    )

    private fun snapshotAt(url: HttpUrl, apiKey: String? = null, version: Long = 1L) =
        ModelRuntimeConfig(baseUrl = url, apiKey = apiKey?.let { SecretValue.of(it) }, version = version)

    @Test
    fun `snapshot is captured once per request and used for the whole request`() = runBlocking {
        val provider = FakeSnapshotProvider(snapshotAt(server.url("/")))
        val model = OllamaModelProvider(provider)
        server.enqueue(outerResponse(JsonPrimitive("""{"route":"REJECT","intents":[]}""").toString()))

        model.generate(request())

        assertEquals(1, provider.calls)
        assertEquals("/api/chat", server.takeRequest().path)
    }

    @Test
    fun `config change takes effect on the next request without restart`() = runBlocking {
        val serverB = MockWebServer()
        serverB.start()
        try {
            val provider = FakeSnapshotProvider(snapshotAt(server.url("/")))
            val model = OllamaModelProvider(provider)
            server.enqueue(outerResponse(JsonPrimitive("""{"route":"REJECT","intents":[]}""").toString()))
            model.generate(request())

            // User changes the config — next request uses the new snapshot.
            provider.snapshot = snapshotAt(serverB.url("/"), version = 2L)
            serverB.enqueue(outerResponse(JsonPrimitive("""{"route":"REJECT","intents":[]}""").toString()))
            model.generate(request())

            assertEquals(2, provider.calls)
            assertEquals("/api/chat", server.takeRequest().path)
            assertEquals("/api/chat", serverB.takeRequest().path)
        } finally {
            serverB.shutdown()
        }
    }

    @Test
    fun `authorization header is present only when a key is set`() = runBlocking {
        val noKey = FakeSnapshotProvider(snapshotAt(server.url("/"), apiKey = null))
        val withKey = FakeSnapshotProvider(snapshotAt(server.url("/"), apiKey = "sk-test-123"))
        val plain = OllamaModelProvider(noKey)
        val secured = OllamaModelProvider(withKey)

        server.enqueue(outerResponse(JsonPrimitive("""{"route":"REJECT","intents":[]}""").toString()))
        plain.generate(request())
        assertNull(server.takeRequest().getHeader("Authorization"))

        server.enqueue(outerResponse(JsonPrimitive("""{"route":"REJECT","intents":[]}""").toString()))
        secured.generate(request())
        assertEquals("Bearer sk-test-123", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `config snapshot failure maps to CONFIGURATION_ERROR and refuses the request`() = runBlocking {
        val provider = ModelConfigSnapshotProvider {
            throw ModelConfigException(ModelConfigErrorCode.DECRYPTION_FAILED, "密钥解密失败或 Keystore 失效")
        }
        val model = OllamaModelProvider(provider)

        val e = try {
            model.generate(request())
            null
        } catch (ex: ModelClientException) {
            ex
        }
        assertNotNull(e)
        assertEquals(ModelErrorKind.CONFIGURATION_ERROR, e!!.kind)
    }

    @Test
    fun `legacy OllamaConfig constructor still works and targets its base url`() = runBlocking {
        val model = OllamaModelProvider(OllamaConfig(baseUrl = server.url("/").toString()))
        server.enqueue(outerResponse(JsonPrimitive("""{"route":"REJECT","intents":[]}""").toString()))
        model.generate(request())
        assertEquals("/api/chat", server.takeRequest().path)
    }
}
