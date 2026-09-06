package net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * CR-011 验证设计 · HTTP_COMPATIBLE Embedding Provider 契约：成功、批量、超时、
 * 限流、鉴权失败、错误维度、NaN/Infinity、重试终止。
 */
class HttpCompatibleEmbeddingProviderTest {

    private lateinit var server: HttpServer
    private val requestCount = AtomicInteger(0)

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        requestCount.set(0)
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun port() = server.address.port

    private fun config(
        batchSize: Int = 16,
        maxRetries: Int = 2,
        timeoutMs: Long = 3000,
        dimension: Int = 3,
        allowedHosts: List<String> = emptyList()
    ) = EmbeddingConfig(
        providerType = "HTTP_COMPATIBLE",
        baseUrl = "http://127.0.0.1:${port()}",
        modelId = "embed-model",
        modelVersion = "v1",
        dimension = dimension,
        timeoutMs = timeoutMs,
        maxRetries = maxRetries,
        batchSize = batchSize,
        allowedHosts = allowedHosts
    )

    private fun provider(cfg: EmbeddingConfig) = HttpCompatibleEmbeddingProvider(
        config = cfg,
        allowInsecureHttp = true // 测试用 http
    )

    private fun json(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun empty(ex: HttpExchange, status: Int) {
        // 必须消费请求体，否则 keep-alive 连接残留数据破坏后续重试请求。
        ex.requestBody.readBytes()
        ex.sendResponseHeaders(status, -1)
        ex.responseBody.close()
    }

    private fun embedResponse(dim: Int = 3, count: Int = 1): String {
        val data = (0 until count).joinToString(",") { i ->
            val vec = (0 until dim).joinToString(",") { d -> "0.$i${d}" }
            """{"embedding":[$vec],"index":$i}"""
        }
        return """{"data":[$data],"model":"embed-model"}"""
    }

    @Test
    fun `成功嵌入并校验维度与数量`() {
        server.createContext("/embeddings") { ex ->
            requestCount.incrementAndGet()
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            assertTrue(body.contains("\"model\":\"embed-model\""))
            assertTrue(body.contains("\"input\":[\"空调\"]"))
            json(ex, 200, embedResponse(dim = 3, count = 1))
        }
        val resp = runBlocking { provider(config(dimension = 3)).embed(EmbeddingRequest(listOf("空调"))) }
        assertEquals(1, resp.vectors.size)
        assertEquals(3, resp.vectors[0].size)
        assertEquals("embed-model", resp.modelId)
    }

    @Test
    fun `批量按 batchSize 分片并合并`() {
        server.createContext("/embeddings") { ex ->
            requestCount.incrementAndGet()
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val n = Regex("文本").findAll(body).count()
            json(ex, 200, embedResponse(dim = 2, count = n))
        }
        val texts = (0 until 5).map { "文本$it" }
        val resp = runBlocking { provider(config(batchSize = 2, dimension = 2)).embed(EmbeddingRequest(texts, batchSize = 2)) }
        assertEquals(5, resp.vectors.size)
        assertEquals(2, resp.vectors[0].size)
        assertEquals(3, requestCount.get()) // 5 条按 batchSize=2 → 3 次请求
    }

    @Test
    fun `5xx 触发有界重试并在重试成功后返回`() {
        val failFirst = AtomicInteger(0)
        server.createContext("/embeddings") { ex ->
            requestCount.incrementAndGet()
            if (failFirst.incrementAndGet() <= 1) {
                empty(ex, 503)
            } else {
                json(ex, 200, embedResponse(dim = 3, count = 1))
            }
        }
        val resp = runBlocking { provider(config(maxRetries = 3)).embed(EmbeddingRequest(listOf("空调"))) }
        assertEquals(1, resp.vectors.size)
        assertTrue(failFirst.get() >= 2)
    }

    @Test
    fun `鉴权失败返回 IVAI-RAG-002 且不重试`() {
        server.createContext("/embeddings") { ex ->
            requestCount.incrementAndGet()
            empty(ex, 401)
        }
        val ex = assertThrows(RagException::class.java) {
            runBlocking { provider(config(maxRetries = 3)).embed(EmbeddingRequest(listOf("空调"))) }
        }
        assertEquals(RagErrorCode.EMBEDDING_UNAVAILABLE, ex.errorCode)
        assertEquals(1, requestCount.get()) // 401 不重试
    }

    @Test
    fun `错误维度标记 Provider 配置错误`() {
        server.createContext("/embeddings") { ex ->
            requestCount.incrementAndGet()
            json(ex, 200, embedResponse(dim = 5, count = 1))
        }
        val ex = assertThrows(RagException::class.java) {
            runBlocking { provider(config(dimension = 3)).embed(EmbeddingRequest(listOf("空调"))) }
        }
        assertEquals(RagErrorCode.INVALID_VECTOR, ex.errorCode)
    }

    @Test
    fun `越界非法数值响应被拒绝`() {
        // kotlinx 在 JSON 解码阶段即拒绝越界浮点（1e309 → Infinity）；Provider 的
        // validateVectors 作为防御性校验兜底。数值非法一律不进入索引。
        server.createContext("/embeddings") { ex ->
            requestCount.incrementAndGet()
            json(ex, 200, """{"data":[{"embedding":[1e309,0.1,0.2],"index":0}],"model":"embed-model"}""")
        }
        val ex = assertThrows(RagException::class.java) {
            runBlocking { provider(config(dimension = 3)).embed(EmbeddingRequest(listOf("空调"))) }
        }
        assertTrue(ex.errorCode == RagErrorCode.INVALID_VECTOR || ex.errorCode == RagErrorCode.EMBEDDING_UNAVAILABLE)
    }

    @Test
    fun `重试耗尽返回 IVAI-RAG-002`() {
        server.createContext("/embeddings") { ex ->
            requestCount.incrementAndGet()
            empty(ex, 500)
        }
        val ex = assertThrows(RagException::class.java) {
            runBlocking { provider(config(maxRetries = 1)).embed(EmbeddingRequest(listOf("空调"))) }
        }
        assertEquals(RagErrorCode.EMBEDDING_UNAVAILABLE, ex.errorCode)
    }

    @Test
    fun `Host 不在白名单时拒绝请求`() {
        val ex = assertThrows(RagException::class.java) {
            runBlocking { provider(config(allowedHosts = listOf("allowed.example.com"))).embed(EmbeddingRequest(listOf("空调"))) }
        }
        assertEquals(RagErrorCode.EMBEDDING_UNAVAILABLE, ex.errorCode)
    }

    @Test
    fun `非 HTTPS 且未放行时拒绝请求`() {
        val cfg = config()
        val ex = assertThrows(RagException::class.java) {
            runBlocking {
                HttpCompatibleEmbeddingProvider(config = cfg, allowInsecureHttp = false)
                    .embed(EmbeddingRequest(listOf("空调")))
            }
        }
        assertEquals(RagErrorCode.EMBEDDING_UNAVAILABLE, ex.errorCode)
    }
}
