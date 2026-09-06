package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.LocalEmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.DistanceMetric
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.FileVectorPersistence
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.IndexedDocument
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.LocalExactVectorStore
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorIndexBuildInput
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolAvailability
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolExecutionBinding
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolPolicy
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * CR-011 验证设计 · L1 Tool/Intent RAG 检索：先过滤再检索（Domain/OperationType/
 * Capability Pack/Runtime Capability）、canonical 去重、治理加权、Top-K 截断与
 * 可用性过滤。
 */
class ToolRagRetrieverTest {

    @TempDir
    lateinit var tmp: File

    private val embedding = LocalEmbeddingProvider()

    private fun tool(
        toolId: String,
        name: String,
        examples: List<String>,
        domain: BusinessDomainId,
        pack: String,
        ops: Set<OperationType>,
        availability: ToolAvailability = ToolAvailability()
    ) = ToolDefinition(
        toolId = toolId,
        functionId = null,
        name = name,
        description = name,
        positiveExamples = examples,
        negativeExamples = emptyList(),
        selectionPriority = 1,
        parameterSchema = "{}",
        policy = ToolPolicy(),
        execution = ToolExecutionBinding("mock-governed", "invoke"),
        domainId = domain,
        capabilityPackId = pack,
        supportedOperations = ops,
        availability = availability
    )

    private val t1 = tool(
        "climate.power.set", "设置空调电源", listOf("打开空调", "关闭空调"),
        BusinessDomainId.CABIN_COMFORT, "cabin.climate", setOf(OperationType.CONTROL)
    )
    private val t2 = tool(
        "media.music.play", "播放音乐", listOf("播放音乐"),
        BusinessDomainId.MEDIA_ENTERTAINMENT, "media.playback", setOf(OperationType.PLAYBACK)
    )

    private suspend fun buildIndex(
        store: LocalExactVectorStore,
        docs: List<IndexedDocument>
    ) {
        val response = embedding.embed(net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingRequest(docs.map { it.text }))
        store.build(
            VectorIndexBuildInput(
                namespace = "tool-intent",
                documents = docs,
                vectors = response.vectors,
                providerType = "LOCAL",
                modelId = embedding.descriptor.modelId,
                modelVersion = null,
                dimension = embedding.descriptor.dimension,
                distanceMetric = DistanceMetric.COSINE,
                documentBuilderVersion = "test",
                governanceVersion = "gv-1",
                contentSetHash = "set-1",
                indexVersion = "v1"
            )
        )
    }

    private fun registryWith(vararg tools: ToolDefinition): ToolRegistry {
        val r = ToolRegistry()
        tools.forEach { r.register(it) }
        return r
    }

    private fun indexed(t: ToolDefinition): IndexedDocument = IndexedDocument(
        documentId = "tool:${t.toolId}",
        text = t.name, // 相同文本 → 相同向量，基分相等，便于隔离测试加权
        metadata = mapOf(
            RetrievalMetadataKeys.CANONICAL_ID to t.toolId,
            RetrievalMetadataKeys.ASSET_TYPE to "TOOL",
            RetrievalMetadataKeys.DOMAIN_IDS to t.domainId.code,
            RetrievalMetadataKeys.OPERATION_TYPES to t.supportedOperations.joinToString(",") { it.name },
            RetrievalMetadataKeys.CAPABILITY_PACK_IDS to t.capabilityPackId
        ),
        contentHash = t.toolId
    )

    @Test
    fun `治理加权使名称命中的 Tool 排前`() = runTest {
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "w")))
        buildIndex(store, listOf(indexed(t1), indexed(t2)))
        val retriever = ToolRagRetriever(registryWith(t1, t2), store, embedding)
        val hits = retriever.retrieve(ToolRetrievalQuery(text = "设置空调电源"), topK = 2)
        assertEquals("climate.power.set", hits.first().toolId)
        assertTrue(hits.first().score > hits.last().score)
    }

    @Test
    fun `Domain 过滤先过滤再检索`() = runTest {
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "d")))
        buildIndex(store, listOf(indexed(t1), indexed(t2)))
        val retriever = ToolRagRetriever(registryWith(t1, t2), store, embedding)
        val hits = retriever.retrieve(
            ToolRetrievalQuery(text = "设置空调电源", domainIds = listOf(BusinessDomainId.CABIN_COMFORT)),
            topK = 2
        )
        assertEquals(listOf("climate.power.set"), hits.map { it.toolId })
    }

    @Test
    fun `OperationType 过滤排除不匹配资产`() = runTest {
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "o")))
        buildIndex(store, listOf(indexed(t1), indexed(t2)))
        val retriever = ToolRagRetriever(registryWith(t1, t2), store, embedding)
        val hits = retriever.retrieve(
            ToolRetrievalQuery(text = "设置空调电源", operationTypes = listOf(OperationType.PLAYBACK)),
            topK = 2
        )
        assertEquals(listOf("media.music.play"), hits.map { it.toolId })
    }

    @Test
    fun `RuntimeCapability 过滤限制统一候选子集`() = runTest {
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "r")))
        buildIndex(store, listOf(indexed(t1), indexed(t2)))
        val retriever = ToolRagRetriever(registryWith(t1, t2), store, embedding)
        val hits = retriever.retrieve(
            ToolRetrievalQuery(text = "设置空调电源", runtimeCapabilityToolIds = setOf("media.music.play")),
            topK = 2
        )
        assertEquals(listOf("media.music.play"), hits.map { it.toolId })
    }

    @Test
    fun `Top-K 截断返回不超过上限`() = runTest {
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "k")))
        buildIndex(store, listOf(indexed(t1), indexed(t2)))
        val retriever = ToolRagRetriever(registryWith(t1, t2), store, embedding)
        val hits = retriever.retrieve(ToolRetrievalQuery(text = "设置空调电源"), topK = 1)
        assertEquals(1, hits.size)
    }

    @Test
    fun `canonical ID 去重不重复占用槽位`() = runTest {
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "c")))
        // 同一 canonical 资产的两个分片（主文档 + Alias 分片）。
        val aliasSlice = indexed(t1).copy(documentId = "alias:climate.power.set")
        buildIndex(store, listOf(indexed(t1), aliasSlice))
        val retriever = ToolRagRetriever(registryWith(t1), store, embedding)
        val hits = retriever.retrieve(ToolRetrievalQuery(text = "设置空调电源"), topK = 5)
        assertEquals(1, hits.size)
        assertEquals("climate.power.set", hits.single().toolId)
    }

    @Test
    fun `车型不可用资产被过滤`() = runTest {
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "v")))
        val t1Restricted = t1.copy(availability = ToolAvailability(vehicleModels = listOf("model-x")))
        val registry = registryWith(t1Restricted, t2)
        buildIndex(store, listOf(indexed(t1Restricted), indexed(t2)))
        val retriever = ToolRagRetriever(registry, store, embedding)
        val hits = retriever.retrieve(
            ToolRetrievalQuery(text = "设置空调电源", vehicleModel = "model-y"),
            topK = 2
        )
        assertTrue(hits.none { it.toolId == "climate.power.set" })
    }
}
