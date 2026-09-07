package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * CR-011 补齐 · Embedding 连接测试：2xx → 成功、401/403 → 鉴权失败、5xx →
 * 服务错误、非法/空配置 → 明确失败。
 */
class EmbeddingConnectionTesterTest {

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

    private fun config(baseUrl: String = server.url("/v1").toString()) = EmbeddingConfig(
        providerType = "HTTP_COMPATIBLE",
        baseUrl = baseUrl.trimEnd('/'),
        modelId = "embed-v3",
        dimension = 768,
        timeoutMs = 3_000L,
        maxRetries = 2,
        batchSize = 16
    )

    private val tester = EmbeddingConnectionTester()

    @Test
    fun `2xx 返回成功并标记 embeddings 方法`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[],"model":"embed-v3"}"""))
        val result = tester.test(config(), apiKey = null)
        assertTrue(result is ConnectionTestResult.Success)
        assertEquals("embeddings", (result as ConnectionTestResult.Success).testMethod)
        // 请求体应包含 model 与 ping 探测文本。
        val recorded = server.takeRequest().body.readUtf8()
        assertTrue(recorded.contains("\"model\":\"embed-v3\""))
        assertTrue(recorded.contains("ping"))
    }

    @Test
    fun `401 返回鉴权失败`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(tester.test(config(), apiKey = "secret") is ConnectionTestResult.Unauthorized)
    }

    @Test
    fun `403 返回鉴权失败`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(tester.test(config(), apiKey = "secret") is ConnectionTestResult.Unauthorized)
    }

    @Test
    fun `5xx 返回服务错误`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = tester.test(config(), apiKey = null)
        assertTrue(result is ConnectionTestResult.InvalidResponse)
        assertTrue((result as ConnectionTestResult.InvalidResponse).detail.contains("503"))
    }

    @Test
    fun `未配置在线嵌入返回明确失败`() = runBlocking {
        val result = tester.test(EmbeddingConfig(), apiKey = null)
        assertTrue(result is ConnectionTestResult.InvalidResponse)
    }

    @Test
    fun `非法配置返回明确失败且不发请求`() = runBlocking {
        val result = tester.test(config(baseUrl = "not-a-url"), apiKey = null)
        assertTrue(result is ConnectionTestResult.InvalidResponse)
        assertEquals(0, server.requestCount)
    }
}
