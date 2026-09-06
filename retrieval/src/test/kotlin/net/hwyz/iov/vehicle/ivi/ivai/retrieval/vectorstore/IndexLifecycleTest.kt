package net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingResponse
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.LocalEmbeddingProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * CR-011 验证设计 · 索引生命周期：首次全量构建、兼容键与内容未变不重建、
 * contentHash 增量更新（未变文档复用旧向量）、模型切换全量重建。
 */
class IndexLifecycleTest {

    @TempDir
    lateinit var tmp: File

    private val embedding = LocalEmbeddingProvider()

    private fun lifecycle(): IndexLifecycle {
        val persistence = FileVectorPersistence(File(tmp, "lifecycle"))
        return IndexLifecycle(LocalExactVectorStore(persistence), persistence, embedding)
    }

    private fun request(
        documents: List<IndexedDocument>,
        modelId: String = "embed-a",
        dimension: Int = 64,
        documentBuilderVersion: String = "builder-1",
        indexVersion: String = "v1"
    ) = IndexBuildRequest(
        namespace = "tool-intent",
        documents = documents,
        providerType = "LOCAL",
        modelId = modelId,
        modelVersion = null,
        dimension = dimension,
        distanceMetric = DistanceMetric.COSINE,
        documentBuilderVersion = documentBuilderVersion,
        governanceVersion = "gv-1",
        indexVersion = indexVersion
    )

    @Test
    fun `首次构建执行全量构建`() = runTest {
        val lc = lifecycle()
        val docs = listOf(
            IndexedDocument("t1", "空调", mapOf(), contentHash = "h1"),
            IndexedDocument("t2", "导航", mapOf(), contentHash = "h2")
        )
        val manifest = lc.ensureIndex(request(docs))
        assertEquals(2, manifest.documentCount)
        assertEquals("v1", manifest.indexVersion)
    }

    @Test
    fun `内容未变时重复构建为 no-op`() = runTest {
        val lc = lifecycle()
        val docs = listOf(
            IndexedDocument("t1", "空调", mapOf(), contentHash = "h1"),
            IndexedDocument("t2", "导航", mapOf(), contentHash = "h2")
        )
        val first = lc.ensureIndex(request(docs, indexVersion = "v1"))
        val second = lc.ensureIndex(request(docs, indexVersion = "v1"))
        assertEquals(first.indexId, second.indexId)
        assertEquals(first.createdAtEpochMillis, second.createdAtEpochMillis)
    }

    @Test
    fun `内容哈希变化时增量更新只重新嵌入变化文档`() = runTest {
        val lc = lifecycle()
        val v1 = listOf(
            IndexedDocument("t1", "空调", mapOf(), contentHash = "h1"),
            IndexedDocument("t2", "导航", mapOf(), contentHash = "h2")
        )
        val first = lc.ensureIndex(request(v1, indexVersion = "v1"))
        // t2 内容变化，t1 不变 → 只重嵌 t2。
        val v2 = listOf(
            IndexedDocument("t1", "空调", mapOf(), contentHash = "h1"),
            IndexedDocument("t2", "导航更新版", mapOf(), contentHash = "h3")
        )
        val second = lc.ensureIndex(request(v2, indexVersion = "v2"))
        assertNotEquals(first.contentSetHash, second.contentSetHash)
        assertEquals(2, second.documentCount)
        // 重新查询应能命中更新后的 t2（向量变化）。
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "lifecycle")))
        val hits = store.search(
            VectorQuery("tool-intent", embedding.embed(EmbeddingRequest(listOf("导航更新版"))).vectors.first(), 5)
        )
        assertEquals("t2", hits.first().documentId)
    }

    @Test
    fun `模型切换触发全量重建`() = runTest {
        val lc = lifecycle()
        val docs = listOf(IndexedDocument("t1", "空调", mapOf(), contentHash = "h1"))
        val first = lc.ensureIndex(request(docs, modelId = "embed-a", indexVersion = "v1"))
        val second = lc.ensureIndex(request(docs, modelId = "embed-b", indexVersion = "v2"))
        assertNotEquals(first.indexId, second.indexId)
        assertNotEquals(first.embeddingProviderType + first.modelId, second.embeddingProviderType + second.modelId)
        // 新模型索引可检索。
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "lifecycle")))
        val hits = store.search(
            VectorQuery("tool-intent", embedding.embed(EmbeddingRequest(listOf("空调"))).vectors.first(), 5)
        )
        assertEquals(1, hits.size)
    }

    @Test
    fun `文档构建版本变化触发全量重建`() = runTest {
        val lc = lifecycle()
        val docs = listOf(IndexedDocument("t1", "空调", mapOf(), contentHash = "h1"))
        val first = lc.ensureIndex(request(docs, documentBuilderVersion = "builder-1", indexVersion = "v1"))
        val second = lc.ensureIndex(request(docs, documentBuilderVersion = "builder-2", indexVersion = "v2"))
        assertNotEquals(first.indexId, second.indexId)
    }

    @Test
    fun `Embedding 不可用时构建失败且不发布`() = runTest {
        val persistence = FileVectorPersistence(File(tmp, "offline"))
        val unavailable = object : net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingProvider {
            override val descriptor = embedding.descriptor.copy(modelId = "unavailable")
            override val available = false
            override suspend fun embed(request: EmbeddingRequest): EmbeddingResponse =
                throw UnsupportedOperationException("不应被调用")
        }
        val lc = IndexLifecycle(LocalExactVectorStore(persistence), persistence, unavailable)
        val ex = runCatching {
            lc.ensureIndex(request(listOf(IndexedDocument("t1", "x", mapOf(), contentHash = "h1"))))
        }.exceptionOrNull()
        assertEquals("IVAI-RAG-002", (ex as net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagException).errorCode)
        // 未发布任何索引。
        assertEquals(null, persistence.readActive("tool-intent"))
        assertEquals(IndexBuildState.FAILED, persistence.readBuildState("tool-intent"))
    }
}
