package net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * CR-011 验证设计 · LOCAL_EXACT VectorStore：构建/检索、先过滤再检索、minScore、
 * Top-K、归一化点积、命名空间隔离、维度校验与原子发布。
 */
class LocalExactVectorStoreTest {

    @TempDir
    lateinit var tmp: File

    private fun store() = LocalExactVectorStore(FileVectorPersistence(File(tmp, "idx")))

    private fun buildInput(
        namespace: String = "tool-intent",
        docs: List<IndexedDocument>,
        vectors: List<FloatArray>,
        dimension: Int = 3
    ) = VectorIndexBuildInput(
        namespace = namespace,
        documents = docs,
        vectors = vectors,
        providerType = "LOCAL",
        modelId = "embed-a",
        modelVersion = null,
        dimension = dimension,
        distanceMetric = DistanceMetric.COSINE,
        documentBuilderVersion = "builder-1",
        governanceVersion = "gv-1",
        contentSetHash = "content-set-1",
        indexVersion = "v1"
    )

    @Test
    fun `构建后可精确检索最近邻`() = runTest {
        val s = store()
        val docs = listOf(
            IndexedDocument("t1", "空调", mapOf("canonicalId" to "t1", "domainIds" to "CLIMATE")),
            IndexedDocument("t2", "导航", mapOf("canonicalId" to "t2", "domainIds" to "NAV"))
        )
        // 归一化后 t1 与查询更接近。
        val manifest = s.build(
            buildInput(
                docs = docs,
                vectors = listOf(
                    floatArrayOf(1f, 0f, 0f),
                    floatArrayOf(0f, 1f, 0f)
                )
            )
        )
        assertEquals(2, manifest.documentCount)
        val hits = s.search(VectorQuery(namespace = "tool-intent", vector = floatArrayOf(0.9f, 0.1f, 0f), topK = 2))
        assertEquals("t1", hits.first().documentId)
        assertTrue(hits.first().score > 0.9)
    }

    @Test
    fun `先过滤再检索排除非候选`() = runTest {
        val s = store()
        val docs = listOf(
            IndexedDocument("t1", "空调", mapOf("canonicalId" to "t1", "packIds" to "P0")),
            IndexedDocument("t2", "导航", mapOf("canonicalId" to "t2", "packIds" to "P1"))
        )
        s.build(buildInput(docs = docs, vectors = listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))))
        val hits = s.search(
            VectorQuery(
                namespace = "tool-intent",
                vector = floatArrayOf(0f, 1f, 0f),
                topK = 5,
                filter = { it["packIds"] == "P0" }
            )
        )
        assertEquals(1, hits.size)
        assertEquals("t1", hits.first().documentId)
    }

    @Test
    fun `minScore 阈值过滤低分候选`() = runTest {
        val s = store()
        val docs = listOf(
            IndexedDocument("t1", "a", mapOf()),
            IndexedDocument("t2", "b", mapOf())
        )
        s.build(buildInput(docs = docs, vectors = listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))))
        val hits = s.search(
            VectorQuery(namespace = "tool-intent", vector = floatArrayOf(0f, 1f, 0f), topK = 5, minScore = 0.99)
        )
        assertEquals(1, hits.size)
        assertEquals("t2", hits.first().documentId)
    }

    @Test
    fun `命名空间隔离互不干扰`() = runTest {
        val s = store()
        s.build(buildInput(namespace = "tool-intent", docs = listOf(IndexedDocument("t1", "x", mapOf())), vectors = listOf(floatArrayOf(1f, 0f, 0f))))
        s.build(buildInput(namespace = "knowledge", docs = listOf(IndexedDocument("k1", "y", mapOf())), vectors = listOf(floatArrayOf(0f, 1f, 0f))))
        assertTrue(s.search(VectorQuery("tool-intent", floatArrayOf(1f, 0f, 0f), 5)).isNotEmpty())
        assertTrue(s.search(VectorQuery("knowledge", floatArrayOf(1f, 0f, 0f), 5)).isNotEmpty())
        assertTrue(s.search(VectorQuery("tool-intent", floatArrayOf(0f, 1f, 0f), 5, minScore = 0.99)).isEmpty())
    }

    @Test
    fun `非法维度拒绝写入`() = runTest {
        val s = store()
        val ex = runCatching {
            s.build(buildInput(docs = listOf(IndexedDocument("t1", "x", mapOf())), vectors = listOf(floatArrayOf(1f, 0f))))
        }.exceptionOrNull()
        assertEquals(RagErrorCode.INVALID_VECTOR, (ex as RagException).errorCode)
    }

    @Test
    fun `NaN 向量拒绝写入`() = runTest {
        val s = store()
        val ex = runCatching {
            s.build(buildInput(docs = listOf(IndexedDocument("t1", "x", mapOf())), vectors = listOf(floatArrayOf(Float.NaN, 0f, 0f))))
        }.exceptionOrNull()
        assertEquals(RagErrorCode.INVALID_VECTOR, (ex as RagException).errorCode)
    }

    @Test
    fun `open 校验兼容键不一致拒绝`() = runTest {
        val s = store()
        val manifest = s.build(
            buildInput(docs = listOf(IndexedDocument("t1", "x", mapOf())), vectors = listOf(floatArrayOf(1f, 0f, 0f)))
        )
        val expected = manifest.copy(modelId = "embed-b")
        val ex = runCatching { s.open(expected) }.exceptionOrNull()
        assertEquals(RagErrorCode.MANIFEST_INCOMPATIBLE, (ex as RagException).errorCode)
    }

    @Test
    fun `构建后持久化可重新加载`() = runTest {
        val dir = File(tmp, "persist")
        val s1 = LocalExactVectorStore(FileVectorPersistence(dir))
        s1.build(buildInput(docs = listOf(IndexedDocument("t1", "x", mapOf())), vectors = listOf(floatArrayOf(1f, 0f, 0f))))
        // 新实例从持久化加载。
        val s2 = LocalExactVectorStore(FileVectorPersistence(dir))
        val hits = s2.search(VectorQuery("tool-intent", floatArrayOf(1f, 0f, 0f), 5))
        assertEquals(1, hits.size)
        assertEquals("t1", hits.first().documentId)
        // 仅一次构建：上一有效索引应为空。
        assertTrue(FileVectorPersistence(dir).readPrevious("tool-intent") == null)
    }
}
