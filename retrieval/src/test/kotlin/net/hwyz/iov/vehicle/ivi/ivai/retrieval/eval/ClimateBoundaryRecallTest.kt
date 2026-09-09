package net.hwyz.iov.vehicle.ivi.ivai.retrieval.eval

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.ToolRagRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.ToolRetrievalDocumentBuilder
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.testutil.LexicalEmbeddingProvider
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
 * CR-018 验证设计 · 五类空调 Tool 边界召回（Recall@5=100% 验收）。
 *
 * 使用词法重叠 Embedding（与 CR-017 位置召回同一基准）：对象/动作/槽位证据 +
 * 负边界惩罚 + 强证据召回保障（appendEvidenceCandidates）保证弱向量下五类表达
 * （power/vent/fan.set/fan.adjust/airflow/auto）正确候选始终进入 Top-5。
 */
class ClimateBoundaryRecallTest {

    @TempDir
    lateinit var tmp: File

    private suspend fun buildRetriever(): ToolRagRetriever {
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        val catalog = GovernanceWorkspace.catalog
        val runtimeSet = RuntimeCapabilitySet(
            selectedPackIds = emptySet(),
            runtimeCandidateToolIds = catalog.tools.map { it.toolId }.toSet(),
            runtimeCandidateWorkflowIds = WorkflowRegistry.ALL.map { it.workflowId }.toSet(),
            governanceVersion = "ivai-governance-v1"
        )
        val embedding = LexicalEmbeddingProvider()
        val docs = ToolRetrievalDocumentBuilder(registry, WorkflowRegistry)
            .buildAll(catalog, runtimeSet, "1.0")
            .map { it.toIndexedDocument() }
        val vectors = embedding.embed(EmbeddingRequest(docs.map { it.text })).vectors
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "l1-climate")))
        store.build(
            VectorIndexBuildInput(
                namespace = "tool-intent", documents = docs, vectors = vectors,
                providerType = "LOCAL", modelId = embedding.descriptor.modelId, modelVersion = null,
                dimension = embedding.descriptor.dimension, distanceMetric = DistanceMetric.COSINE,
                documentBuilderVersion = "tool-builder-3", governanceVersion = "ivai-governance-v1",
                contentSetHash = "l1-climate", indexVersion = "v1"
            )
        )
        return ToolRagRetriever(registry, store, embedding)
    }

    @Test
    fun `五类空调表达正确 Tool Recall@5 为 100 pct`() = runTest {
        val retriever = buildRetriever()
        val cases = mapOf(
            // power（HVAC_SYSTEM）
            "打开空调" to "climate.power.set",
            "启动空调系统" to "climate.power.set",
            "接通空调电源" to "climate.power.set",
            // vent（VENT + zone）
            "打开通风口" to "climate.vent.set",
            "打开前排风口" to "climate.vent.set",
            // fan.speed.set（绝对档位）
            "风量调到5档" to "climate.fan.speed.set",
            "中左风量调到5档" to "climate.fan.speed.set",
            // fan.speed.adjust（相对增减）
            "风量调大一点" to "climate.fan.speed.adjust",
            // airflow.mode.set（风向模式）
            "出风模式吹脸" to "climate.airflow.mode.set",
            // auto.set（AUTO）
            "开启自动空调" to "climate.auto.set"
        )
        for ((text, expected) in cases) {
            val top5 = retriever.retrieve(ToolRetrievalQuery(text = text, vehicleModel = "demo"), 5)
                .map { it.toolId }
            assertTrue(
                expected in top5,
                "$text → Top-5 必须包含 $expected，实际: $top5"
            )
        }
    }

    @Test
    fun `负边界惩罚使相似 Tool 不抢占正确候选 Top-1`() = runTest {
        val retriever = buildRetriever()
        // “打开通风口”：power.set 受负边界惩罚，vent.set 应为 Top-1。
        val top1 = retriever.retrieve(ToolRetrievalQuery(text = "打开通风口", vehicleModel = "demo"), 1)
            .firstOrNull()?.toolId
        assertEquals("climate.vent.set", top1)
    }
}
