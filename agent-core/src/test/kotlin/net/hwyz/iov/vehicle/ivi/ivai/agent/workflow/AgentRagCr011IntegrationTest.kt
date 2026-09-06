package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockGovernedToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentRoute
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.FakeRagConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeManager
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSource
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.RagToolCandidateProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.StubModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.LocalEmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.KnowledgeReranker
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.SampleKnowledgeDocs
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.KnowledgeRagRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.ToolRagRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.ToolRetrievalDocumentBuilder
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.toIndexedDocument
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.DistanceMetric
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.FileVectorPersistence
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.LocalExactVectorStore
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorIndexBuildInput
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.RuntimeCapabilitySet
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.AdapterRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.DefaultToolExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * CR-011 验证设计 · Agent 集成：L1 分支接入 ToolRagRetriever（向量检索候选受
 * 统一运行时候选集约束）、L0/RuntimeCapabilityAssembler/安全执行链保持不变、
 * L2 分支接入 KnowledgeRagRetriever（证据化回答），双路径独立启停。
 */
class AgentRagCr011IntegrationTest {

    @TempDir
    lateinit var tmp: File

    private val embedding = LocalEmbeddingProvider()

    private fun input(requestId: String, text: String) =
        AgentInput(requestId = requestId, text = text, source = "mock")

    private suspend fun buildToolIndex(
        registry: ToolRegistry,
        toolIds: Set<String>
    ): ToolRagRetriever {
        val catalog = GovernanceWorkspace.catalog
        val runtimeSet = RuntimeCapabilitySet(
            selectedPackIds = emptySet(),
            runtimeCandidateToolIds = toolIds,
            runtimeCandidateWorkflowIds = emptySet(),
            governanceVersion = "ivai-governance-v1"
        )
        val docs = ToolRetrievalDocumentBuilder(registry, WorkflowRegistry)
            .buildAll(catalog, runtimeSet, "1.0")
            .map { it.toIndexedDocument() }
        val vectors = embedding.embed(EmbeddingRequest(docs.map { it.text })).vectors
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "tool")))
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
                contentSetHash = "tool-set",
                indexVersion = "v1"
            )
        )
        return ToolRagRetriever(registry, store, embedding)
    }

    private suspend fun buildKnowledgeRetriever(): KnowledgeRagRetriever {
        val chunks = SampleKnowledgeDocs.chunks
        val docs = chunks.map { it.toIndexedDocument() }
        val vectors = embedding.embed(EmbeddingRequest(docs.map { it.text })).vectors
        val store = LocalExactVectorStore(FileVectorPersistence(File(tmp, "knowledge")))
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
                contentSetHash = "knowledge-set",
                indexVersion = "v1"
            )
        )
        val byId = chunks.associateBy { it.chunkId }
        return KnowledgeRagRetriever(store, embedding, chunkResolver = { byId[it] })
    }

    /**
     * 手动装配（治理注册表 + 治理 Mock Adapter + 双路径向量 RAG），与
     * AgentService.buildAgentGraph 使用同一治理资产模型。
     */
    private suspend fun vectorRagGraph(
        model: ModelProvider,
        registry: ToolRegistry,
        toolIndexIds: Set<String>,
        config: RagRuntimeConfig = RagRuntimeConfig(
            enabled = true, toolRagEnabled = true, knowledgeRagEnabled = true,
            toolTopK = 10, knowledgeTopK = 5
        ),
        knowledgeRetriever: KnowledgeRetriever? = null
    ): Pair<AgentWorkflow, MockGovernedToolAdapter> {
        val governedAdapter = MockGovernedToolAdapter()
        val toolRagRetriever = buildToolIndex(registry, toolIndexIds)
        val effectiveKnowledge = knowledgeRetriever ?: buildKnowledgeRetriever()
        val validator = net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator(registry)
        val agentPolicy = net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine(
            registry, net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolPolicyEngine()
        )
        val executor = DefaultToolExecutor(
            registry = registry,
            adapterRegistry = AdapterRegistry().register(governedAdapter)
        )
        val ragManager = RagRuntimeManager(
            FakeRagConfigRepository(config),
            toolRetriever = toolRagRetriever,
            knowledgeRetriever = effectiveKnowledge,
            embeddingProvider = embedding
        )
        val workflow = AgentWorkflow(
            modelProvider = model,
            registry = registry,
            router = net.hwyz.iov.vehicle.ivi.ivai.agent.router.Router(),
            promptBuilder = net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptBuilder(registry),
            validator = validator,
            agentPolicy = agentPolicy,
            toolExecutor = executor,
            config = AgentConfig(model = "qwen3.5:4b", ollamaBaseUrl = "http://localhost:11434"),
            tieredRouter = net.hwyz.iov.vehicle.ivi.ivai.agent.router.TieredIntentRouter(
                net.hwyz.iov.vehicle.ivi.ivai.agent.router.DefaultFastIntentMatcher(registry),
                net.hwyz.iov.vehicle.ivi.ivai.agent.router.DomainClassifier(registry),
                domainRouter = null,
                capabilitySelector = null,
                registry = registry
            ),
            toolCandidateProvider = RagToolCandidateProvider(registry, toolRagRetriever),
            vehicleStateProvider = net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider {
                governedAdapter.snapshot()
            },
            ragRuntimeManager = ragManager,
            knowledgeRetriever = effectiveKnowledge,
            knowledgeReranker = if (effectiveKnowledge != null) KnowledgeReranker() else null
        )
        return workflow to governedAdapter
    }

    // ------------------------------------------------------------------ L0 保持

    @Test
    fun `明确表达保持 L0 直达不触碰向量 RAG`() = runTest {
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        var modelCalls = 0
        val throwingModel = object : ModelProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                modelCalls++
                throw IllegalStateException("L0 不得调用模型")
            }
        }
        val (workflow, adapter) = vectorRagGraph(
            throwingModel, registry,
            toolIndexIds = setOf("climate.power.set")
        )
        val result = workflow.process(input("req-l0", "打开空调"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertEquals(0, modelCalls, "L0 直达不应调用模型")
        assertNotNull(result.executionPath)
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, result.executionPath!!.finalTier)
        assertEquals(CandidateSource.L0_RULE, result.executionPath!!.candidateSource)
        assertTrue(adapter.executedMethods().contains("climate.power.set"))
    }

    // ------------------------------------------------------------------ L1 向量 RAG

    @Test
    fun `隐式表达走 L1 向量 RAG 且模型只能在召回候选内选择`() = runTest {
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        val model = StubModelProvider(
            """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.temperature.set","arguments":{"temperature":26,"zone":"driver"}}],"modelConfidence":0.8,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"IMPLICIT_TEMPERATURE_INTENT"}"""
        )
        val (workflow, adapter) = vectorRagGraph(
            model, registry,
            toolIndexIds = setOf("climate.power.set", "climate.temperature.set", "media.playback.play")
        )
        val result = workflow.process(input("req-l1", "我有点冷想调温度"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertEquals("climate.temperature.set", result.executionResult!!.toolId)
        assertNotNull(result.executionPath)
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, result.executionPath!!.finalTier)
        assertEquals(CandidateSource.L1_LOCAL_LLM, result.executionPath!!.candidateSource)
        assertTrue(adapter.executedMethods().contains("climate.temperature.set"))
    }

    @Test
    fun `L1 模型输出不在向量召回候选内的 Tool 被阻止`() = runTest {
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        val model = StubModelProvider(
            """{"route":"LOCAL_TOOL","intents":[{"toolId":"media.playback.play","arguments":{}}],"modelConfidence":0.8,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"IMPLICIT_INTENT"}"""
        )
        // 索引只召回空调工具；模型输出 media.playback.play（不在召回候选）→ 阻止。
        val (workflow, adapter) = vectorRagGraph(
            model, registry,
            toolIndexIds = setOf("climate.power.set", "climate.temperature.set")
        )
        val result = workflow.process(input("req-l1-out", "帮我调温度"), Session())

        assertEquals(AgentState.REJECTED, result.state)
        assertEquals("IVAI-TOOL-003", result.errorCode)
        assertFalse(adapter.executedMethods().contains("media.playback.play"))
    }

    // ------------------------------------------------------------------ L2 向量知识

    @Test
    fun `本地知识问答走 L2 向量 RAG 返回带来源回答且不产生 Tool Call`() = runTest {
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        val model = object : ModelProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse =
                ModelResponse(
                    requestId = request.requestId,
                    content = "胎压报警指轮胎气压过低，请安全停车检查并补气。（来源：故障·胎压报警说明 v1.0）",
                    contentJson = null,
                    model = "qwen3.5:4b",
                    finishReason = "stop",
                    latencyMs = 3
                )
        }
        val (workflow, _) = vectorRagGraph(
            model, registry,
            toolIndexIds = setOf("climate.power.set")
        )
        val result = workflow.process(input("req-l2", "胎压报警是什么意思"), Session())

        assertEquals(AgentState.REPLY_READY, result.state)
        assertTrue(result.responseText.contains("胎压报警"))
        assertTrue(result.responseText.contains("来源"))
        assertEquals(AgentRoute.LOCAL_DIALOGUE, result.route)
    }

    // ------------------------------------------------------------------ 双路径独立启停

    @Test
    fun `双路径独立启停互不影响`() = runTest {
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        // 仅启用 L1（Knowledge RAG 关闭）。
        val model = StubModelProvider(
            """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.temperature.set","arguments":{"temperature":26,"zone":"driver"}}],"modelConfidence":0.8,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"IMPLICIT_TEMPERATURE_INTENT"}"""
        )
        val (workflow, _) = vectorRagGraph(
            model, registry,
            toolIndexIds = setOf("climate.power.set", "climate.temperature.set"),
            config = RagRuntimeConfig(
                enabled = true, toolRagEnabled = true, knowledgeRagEnabled = false,
                toolTopK = 10
            ),
            knowledgeRetriever = null
        )
        // L1 正常执行。
        val l1 = workflow.process(input("req-a", "我有点冷想调温度"), Session())
        assertEquals(AgentState.SUCCEEDED, l1.state)
        assertEquals("climate.temperature.set", l1.executionResult!!.toolId)
        // L2 因 Knowledge RAG 关闭 → L3 云（预留），不误入 L1。
        val l2 = workflow.process(input("req-b", "胎压报警是什么意思"), Session())
        assertEquals(AgentState.CLOUD_REQUIRED, l2.state)
        assertTrue(l2.responseText.contains("本地知识库未启用"))
    }
}
