package net.hwyz.iov.vehicle.ivi.ivai.retrieval.eval

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.LocalEmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.DistanceMetric
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.FileVectorPersistence
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.IndexedDocument
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.LocalExactVectorStore
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorIndexBuildInput
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorQuery
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * CR-011 验证设计 · 性能基准：索引构建时间、启动加载、P95 查询时延与索引大小。
 * 阈值进入发布配置和报告（本测试使用宽松上限避免 CI 抖动，真实基准在接入真实
 * Embedding 后评定）。
 */
class RetrievalPerformanceBenchmarkTest {

    @TempDir
    lateinit var tmp: File

    private val embedding = LocalEmbeddingProvider()

    @Test
    fun `千级索引构建与查询时延满足宽松预算`() = runTest {
        val docCount = 1000
        val docs = (0 until docCount).map { i ->
            IndexedDocument(
                documentId = "doc$i",
                text = "功能说明文档 $i：空调、导航、胎压、座椅、音乐、车窗、灯光、充电",
                metadata = mapOf("idx" to i.toString()),
                contentHash = "h$i"
            )
        }

        val buildStart = System.nanoTime()
        val vectors = embedding.embed(EmbeddingRequest(docs.map { it.text })).vectors
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "bench")))
        val manifest = store.build(
            VectorIndexBuildInput(
                namespace = "bench",
                documents = docs,
                vectors = vectors,
                providerType = "LOCAL",
                modelId = embedding.descriptor.modelId,
                modelVersion = null,
                dimension = embedding.descriptor.dimension,
                distanceMetric = DistanceMetric.COSINE,
                documentBuilderVersion = "bench-1",
                governanceVersion = "gv",
                contentSetHash = "bench-set",
                indexVersion = "v1"
            )
        )
        val buildMs = (System.nanoTime() - buildStart) / 1_000_000

        // 启动加载：新实例从持久化加载。
        val loadStart = System.nanoTime()
        val s2 = LocalExactVectorStore(FileVectorPersistence(File(tmp, "bench")))
        s2.open(manifest)
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000

        // P95 查询时延。
        val latencies = mutableListOf<Long>()
        val queryVector = embedding.embed(EmbeddingRequest(listOf("胎压报警怎么处理"))).vectors.first()
        repeat(200) {
            val t = System.nanoTime()
            s2.search(VectorQuery("bench", queryVector, topK = 10))
            latencies += (System.nanoTime() - t) / 1_000_000
        }
        val p95 = latencies.sorted()[95]

        val report = buildString {
            appendLine("索引文档数: ${manifest.documentCount}")
            appendLine("构建耗时: ${buildMs}ms")
            appendLine("启动加载: ${loadMs}ms")
            appendLine("P95 查询时延: ${p95}ms")
        }
        println("=== 性能基准报告 ===\n$report")

        assertTrue(manifest.documentCount == docCount)
        assertTrue(buildMs < 30_000, "千级构建应在 30s 内")
        assertTrue(p95 < 1_000, "P95 查询时延应在 1s 内（宽松 CI 上限）")
    }
}
