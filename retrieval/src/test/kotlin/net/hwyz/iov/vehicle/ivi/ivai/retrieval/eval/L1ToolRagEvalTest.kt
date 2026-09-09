package net.hwyz.iov.vehicle.ivi.ivai.retrieval.eval

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.LocalEmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.ToolRagRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.ToolRetrievalDocumentBuilder
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.DistanceMetric
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.FileVectorPersistence
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.LocalExactVectorStore
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorIndexBuildInput
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.RuntimeCapabilitySet
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * CR-011 验证设计 · L1 Tool/Intent 离线评测：管线可运行、指标在合法区间、
 * 无效候选率为零（只返回统一候选集内资产，安全约束）。真实召回质量需接入真实
 * Embedding 模型后评定。
 */
class L1ToolRagEvalTest {

    @TempDir
    lateinit var tmp: File

    private val embedding = LocalEmbeddingProvider()

    private suspend fun buildRetriever(): ToolRagRetriever {
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        val catalog = GovernanceWorkspace.catalog
        val runtimeSet = RuntimeCapabilitySet(
            selectedPackIds = emptySet(),
            runtimeCandidateToolIds = catalog.tools.map { it.toolId }.toSet(),
            runtimeCandidateWorkflowIds = WorkflowRegistry.ALL.map { it.workflowId }.toSet(),
            governanceVersion = "ivai-governance-v1"
        )
        val docs = ToolRetrievalDocumentBuilder(registry, WorkflowRegistry)
            .buildAll(catalog, runtimeSet, "1.0")
            .map { it.toIndexedDocument() }
        val vectors = embedding.embed(EmbeddingRequest(docs.map { it.text })).vectors
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "l1")))
        store.build(
            VectorIndexBuildInput(
                namespace = "tool-intent",
                documents = docs,
                vectors = vectors,
                providerType = "LOCAL",
                modelId = embedding.descriptor.modelId,
                modelVersion = null,
                dimension = embedding.descriptor.dimension,
                distanceMetric = DistanceMetric.COSINE,
                documentBuilderVersion = "tool-builder-1",
                governanceVersion = "ivai-governance-v1",
                contentSetHash = "l1-set",
                indexVersion = "v1"
            )
        )
        return ToolRagRetriever(registry, store, embedding)
    }

    @Test
    fun `L1 评测集运行且指标合法`() = runTest {
        val retriever = buildRetriever()
        val eligible = GovernanceWorkspace.catalog.tools.map { it.toolId }.toSet()
        val result = RetrievalEvalRunner.evaluate(
            queries = SampleEvalSets.L1_TOOL,
            eligible = eligible,
            topK = 10,
            retrieve = { q, k ->
                retriever.retrieve(
                    ToolRetrievalQuery(
                        text = q.text,
                        domainIds = q.domainIds,
                        operationTypes = q.operationTypes,
                        vehicleModel = q.vehicleModel,
                        softwareVersion = q.softwareVersion
                    ),
                    k
                ).map { it.toolId }
            }
        )
        println("=== L1 Tool/Intent 评测报告 ===\n${result.summary()}")
        assertTrue(result.queryCount == SampleEvalSets.L1_TOOL.size)
        assertEquals(0.0, result.invalidCandidateRate, "不得返回运行时候选集之外资产")
        listOf(result.recallAt1, result.recallAt3, result.recallAtK, result.mrr, result.top1Accuracy)
            .forEach { assertTrue(it in 0.0..1.0) }
    }

    @Test
    fun `CR-017 位置表达正确 Tool Recall@5 为 100 pct`() = runTest {
        // 词法重叠 Embedding：验证展开后的检索文档内容（分区正例/位置 Alias/enum）
        // 确实驱动位置表达的正确召回（正式环境用语义 Embedding，文档内容相同）。
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        val catalog = GovernanceWorkspace.catalog
        val runtimeSet = RuntimeCapabilitySet(
            selectedPackIds = emptySet(),
            runtimeCandidateToolIds = catalog.tools.map { it.toolId }.toSet(),
            runtimeCandidateWorkflowIds = WorkflowRegistry.ALL.map { it.workflowId }.toSet(),
            governanceVersion = "ivai-governance-v1"
        )
        val embedding = net.hwyz.iov.vehicle.ivi.ivai.retrieval.testutil.LexicalEmbeddingProvider()
        val docs = ToolRetrievalDocumentBuilder(registry, WorkflowRegistry)
            .buildAll(catalog, runtimeSet, "1.0")
            .map { it.toIndexedDocument() }
        val vectors = embedding.embed(EmbeddingRequest(docs.map { it.text })).vectors
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "l1-lexical")))
        store.build(
            VectorIndexBuildInput(
                namespace = "tool-intent", documents = docs, vectors = vectors,
                providerType = "LOCAL", modelId = embedding.descriptor.modelId, modelVersion = null,
                dimension = embedding.descriptor.dimension, distanceMetric = DistanceMetric.COSINE,
                documentBuilderVersion = "tool-builder-2", governanceVersion = "ivai-governance-v1",
                contentSetHash = "l1-lexical", indexVersion = "v1"
            )
        )
        val retriever = ToolRagRetriever(registry, store, embedding)
        val positionQueries = listOf(
            "中左风量档位调到5档",
            "中右设置风量档位5档",
            "2排风量档位设为5档",
            "3排风量档位调到5档",
            "中排左风量调到5档",
            "第二排右风量设为5档"
        )
        for (text in positionQueries) {
            val top5 = retriever.retrieve(
                ToolRetrievalQuery(text = text, vehicleModel = "demo"),
                5
            ).map { it.toolId }
            assertTrue(
                "climate.fan.speed.set" in top5,
                "$text → Top-5 必须包含 climate.fan.speed.set，实际: $top5"
            )
        }
    }
}
