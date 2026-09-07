package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import java.io.File
import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.HttpCompatibleEmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.DistanceMetric
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.FileVectorPersistence
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.IndexBuildRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.IndexLifecycle
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.IndexedDocument
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.LocalExactVectorStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * CR-011 补齐 · 运行时接线：持久化 Embedding 配置 → EmbeddingProviderFactory 装配
 * 在线 Provider → IndexLifecycle 真正调用 HTTP 端点构建索引并记录 Manifest；
 * 切换模型（兼容键变化）触发全量重建（IVAI-REQ-097 / IVAI-REQ-102）。
 */
class EmbeddingRuntimeWiringIntegrationTest {

    @TempDir
    lateinit var tmp: File

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

    private fun embedResponse(dim: Int, count: Int): String {
        val data = (0 until count).joinToString(",") { i ->
            val vec = (0 until dim).joinToString(",") { d -> "0.${i + 1}" }
            """{"embedding":[$vec],"index":$i}"""
        }
        return """{"data":[$data],"model":"embed-v3"}"""
    }

    private fun docs(n: Int) = (0 until n).map {
        IndexedDocument(documentId = "doc-$it", text = "空调温度调到$it 度", contentHash = "hash-$it")
    }

    private fun request(modelId: String, dimension: Int) = IndexBuildRequest(
        namespace = "tool-intent",
        documents = docs(2),
        providerType = "HTTP_COMPATIBLE",
        modelId = modelId,
        modelVersion = null,
        dimension = dimension,
        distanceMetric = DistanceMetric.COSINE,
        documentBuilderVersion = "tool-builder-1",
        governanceVersion = "ivai-governance-v1",
        indexVersion = "v1"
    )

    @Test
    fun `configured HTTP embedding builds index and model switch rebuilds`() = runTest {
        val baseUrl = server.url("/v1").toString().trimEnd('/')
        // 首次构建：2 条文档 → 1 次请求（batch 16）。
        server.enqueue(MockResponse().setResponseCode(200).setBody(embedResponse(3, 2)))

        val config = EmbeddingConfig(
            providerType = "HTTP_COMPATIBLE",
            baseUrl = baseUrl,
            modelId = "embed-a",
            dimension = 3,
            timeoutMs = 3_000L,
            maxRetries = 1,
            batchSize = 16
        )
        val provider = EmbeddingProviderFactory.create(config, allowInsecureHttp = true)
        assertInstanceOf(HttpCompatibleEmbeddingProvider::class.java, provider)
        assertTrue(provider.available)

        val persistence = FileVectorPersistence(File(tmp, "idx"))
        val store = LocalExactVectorStore(persistence)
        val lifecycle = IndexLifecycle(store, persistence, provider)

        val first = lifecycle.ensureIndex(request("embed-a", 3))
        assertEquals("embed-a", first.modelId)
        assertEquals(3, first.dimension)
        assertEquals(2, first.documentCount)
        // 确保请求真正打到 HTTP 端点（而非本地桩）。
        val recorded = server.takeRequest().body.readUtf8()
        assertTrue(recorded.contains("\"model\":\"embed-a\""))

        // 切换模型：兼容键变化 → 全量重建，Manifest 记录新模型。
        server.enqueue(MockResponse().setResponseCode(200).setBody(embedResponse(3, 2)))
        val second = lifecycle.ensureIndex(request("embed-b", 3))
        assertEquals("embed-b", second.modelId)
    }
}
