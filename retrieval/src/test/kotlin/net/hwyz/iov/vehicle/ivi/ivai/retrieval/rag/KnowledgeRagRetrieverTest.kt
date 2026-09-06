package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeChunk
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeSourceType
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.LocalEmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.DistanceMetric
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.FileVectorPersistence
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.LocalExactVectorStore
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorIndexBuildInput
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionConstraint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * CR-011 验证设计 · L2 Knowledge RAG 检索：来源/车型/软件版本/语言过滤（先过滤
 * 再检索）、来源多样性、相邻片段合并、Top-K、证据形态与索引隔离。
 */
class KnowledgeRagRetrieverTest {

    @TempDir
    lateinit var tmp: File

    private val embedding = LocalEmbeddingProvider()

    private val tire1 = KnowledgeChunk(
        chunkId = "doc_tire_pressure.s1",
        sourceId = "doc_tire_pressure",
        sourceType = KnowledgeSourceType.FAULT_HELP,
        title = "胎压报警",
        sectionPath = "故障/胎压报警",
        content = "胎压报警 轮胎气压低 安全停车检查",
        vehicleModels = setOf("*"),
        softwareVersions = VersionConstraint(min = "0.1.0"),
        contentHash = "t1"
    )
    private val tire2 = KnowledgeChunk(
        chunkId = "doc_tire_pressure.s2",
        sourceId = "doc_tire_pressure",
        sourceType = KnowledgeSourceType.FAULT_HELP,
        title = "胎压报警补充",
        sectionPath = "故障/胎压报警补充",
        content = "胎压报警 补气 维修店",
        vehicleModels = setOf("*"),
        softwareVersions = VersionConstraint(min = "0.1.0"),
        contentHash = "t2"
    )
    private val climate = KnowledgeChunk(
        chunkId = "doc_climate.s1",
        sourceId = "doc_climate",
        sourceType = KnowledgeSourceType.FEATURE_EXPLANATION,
        title = "空调使用",
        sectionPath = "空调/使用",
        content = "空调 温度 16 到 32",
        vehicleModels = setOf("model-x"),
        contentHash = "c1"
    )

    private suspend fun buildRetriever(chunks: List<KnowledgeChunk>): KnowledgeRagRetriever {
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "k")))
        val docs = chunks.map { it.toIndexedDocument() }
        val vectors = embedding.embed(EmbeddingRequest(docs.map { it.text })).vectors
        store.build(
            VectorIndexBuildInput(
                namespace = "knowledge",
                documents = docs,
                vectors = vectors,
                providerType = "LOCAL",
                modelId = embedding.descriptor.modelId,
                modelVersion = null,
                dimension = embedding.descriptor.dimension,
                distanceMetric = DistanceMetric.COSINE,
                documentBuilderVersion = "test",
                governanceVersion = "gv-1",
                contentSetHash = "ks-1",
                indexVersion = "v1"
            )
        )
        val byId = chunks.associateBy { it.chunkId }
        return KnowledgeRagRetriever(store, embedding, chunkResolver = { byId[it] })
    }

    @Test
    fun `来源过滤只保留已批准来源`() = runTest {
        val retriever = buildRetriever(listOf(tire1, climate))
        val evs = retriever.retrieve(KnowledgeRetrievalQuery(text = "胎压报警", sourceIds = setOf("doc_climate")), topK = 5)
        assertTrue(evs.isNotEmpty())
        assertTrue(evs.none { it.chunk.sourceId == "doc_tire_pressure" })
    }

    @Test
    fun `车型过滤排除不兼容片段`() = runTest {
        val retriever = buildRetriever(listOf(tire1, climate))
        val evs = retriever.retrieve(KnowledgeRetrievalQuery(text = "胎压报警", vehicleModel = "model-y"), topK = 5)
        // climate 仅适用 model-x → 被过滤；tire1 适用全部车型 → 保留。
        assertTrue(evs.all { it.chunk.sourceId == "doc_tire_pressure" })
    }

    @Test
    fun `软件版本过滤排除低于 min 的片段`() = runTest {
        val retriever = buildRetriever(listOf(tire1))
        val evs = retriever.retrieve(KnowledgeRetrievalQuery(text = "胎压报警", softwareVersion = "0.0.5"), topK = 5)
        assertTrue(evs.isEmpty())
    }

    @Test
    fun `语言过滤排除不匹配片段`() = runTest {
        val retriever = buildRetriever(listOf(tire1))
        val evs = retriever.retrieve(KnowledgeRetrievalQuery(text = "胎压报警", language = "en-US"), topK = 5)
        assertTrue(evs.isEmpty())
    }

    @Test
    fun `相邻片段合并保持证据上下文完整`() = runTest {
        val retriever = buildRetriever(listOf(tire1, tire2))
        val evs = retriever.retrieve(KnowledgeRetrievalQuery(text = "胎压报警"), topK = 5)
        // tire1 与 tire2 同源且章节父级均为“故障” → 合并为一条证据。
        val tireEvs = evs.filter { it.chunk.sourceId == "doc_tire_pressure" }
        assertEquals(1, tireEvs.size)
        val merged = tireEvs.single()
        assertTrue(merged.chunk.content.contains("安全停车检查"))
        assertTrue(merged.chunk.content.contains("补气"))
        assertTrue(merged.matchedFields.contains("adjacent-merged"))
    }

    @Test
    fun `来源多样性限制同一来源证据数`() = runTest {
        val retriever = buildRetriever(listOf(tire1, climate))
            .let { it } // maxPerSource 默认 2，足够
        val evs = retriever.retrieve(KnowledgeRetrievalQuery(text = "胎压报警 空调"), topK = 10)
        assertEquals(2, evs.size) // 合并后 tire 1 条 + climate 1 条
    }

    @Test
    fun `Top-K 截断`() = runTest {
        val retriever = buildRetriever(listOf(tire1, tire2, climate))
        val evs = retriever.retrieve(KnowledgeRetrievalQuery(text = "胎压报警 空调"), topK = 1)
        assertTrue(evs.size <= 1)
    }

    @Test
    fun `空结果不编造证据`() = runTest {
        val retriever = buildRetriever(listOf(tire1))
        val evs = retriever.retrieve(KnowledgeRetrievalQuery(text = "量子物理", sourceIds = setOf("no-such-source")), topK = 5)
        assertTrue(evs.isEmpty())
    }
}
