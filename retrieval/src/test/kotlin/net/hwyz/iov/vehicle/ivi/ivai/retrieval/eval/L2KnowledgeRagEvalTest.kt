package net.hwyz.iov.vehicle.ivi.ivai.retrieval.eval

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.LocalEmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.SampleKnowledgeDocs
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.KnowledgeRagRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.toIndexedDocument
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.DistanceMetric
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.FileVectorPersistence
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.LocalExactVectorStore
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorIndexBuildInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * CR-011 验证设计 · L2 Knowledge 离线评测：管线可运行、指标合法、无效候选率
 * 为零（只返回批准 Knowledge Corpus 内来源）、无证据查询尽可能拒答。真实召回
 * 质量需接入真实 Embedding 模型后评定。
 */
class L2KnowledgeRagEvalTest {

    @TempDir
    lateinit var tmp: File

    private val embedding = LocalEmbeddingProvider()

    private suspend fun buildRetriever(): KnowledgeRagRetriever {
        val chunks = SampleKnowledgeDocs.chunks
        val docs = chunks.map { it.toIndexedDocument() }
        val vectors = embedding.embed(EmbeddingRequest(docs.map { it.text })).vectors
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "l2")))
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
                documentBuilderVersion = "knowledge-builder-1",
                governanceVersion = "ivai-governance-v1",
                contentSetHash = "l2-set",
                indexVersion = "v1"
            )
        )
        val byId = chunks.associateBy { it.chunkId }
        return KnowledgeRagRetriever(store, embedding, chunkResolver = { byId[it] })
    }

    @Test
    fun `L2 评测集运行且指标合法`() = runTest {
        val retriever = buildRetriever()
        val eligible = SampleKnowledgeDocs.chunks.map { it.sourceId }.toSet()
        val result = RetrievalEvalRunner.evaluate(
            queries = SampleEvalSets.L2_KNOWLEDGE,
            eligible = eligible,
            topK = 5,
            retrieve = { q, k ->
                retriever.retrieve(
                    KnowledgeRetrievalQuery(
                        text = q.text,
                        vehicleModel = q.vehicleModel,
                        softwareVersion = q.softwareVersion,
                        language = q.language,
                        sourceIds = emptySet()
                    ),
                    k
                ).map { it.chunk.sourceId }
            }
        )
        println("=== L2 Knowledge 评测报告 ===\n${result.summary()}")
        assertTrue(result.queryCount == SampleEvalSets.L2_KNOWLEDGE.size)
        assertEquals(0.0, result.invalidCandidateRate, "不得返回批准 Corpus 之外来源")
        listOf(result.recallAt1, result.recallAt3, result.recallAtK, result.mrr, result.top1Accuracy)
            .forEach { assertTrue(it in 0.0..1.0) }
    }
}
