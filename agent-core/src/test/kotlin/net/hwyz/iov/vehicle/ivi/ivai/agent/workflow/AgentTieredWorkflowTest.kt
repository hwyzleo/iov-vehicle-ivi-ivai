package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockClimateToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.FakeRagConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeManager
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSource
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.RagToolCandidateProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.CollectingAgentEventListener
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.StubModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.TestGraph
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.KnowledgeRetrieverImpl
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.SampleKnowledgeDocs
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.tool.HybridRuleToolRetriever
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-005 集成测试：L0 直达、L1 Tool RAG + 本地模型、L2 Knowledge RAG 自然回答、
 * Top-K 集合外拦截、降级策略与执行路径。
 */
class AgentTieredWorkflowTest {

    private val temperatureIncrease = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.temperature_increase","functionId":"AC_Temperature_2","arguments":{"position":"driver","step":1}}],"modelConfidence":0.9,"riskLevel":"medium","needConfirmation":false,"missingArguments":[],"reasonCode":"IMPLICIT_COLD_INTENT"}"""
    private val powerOn = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power_on","functionId":"AC_Control_1","arguments":{"position":"driver"}}],"modelConfidence":0.98,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"EXPLICIT_INTENT"}"""

    private fun input(requestId: String, text: String) =
        AgentInput(requestId = requestId, text = text, source = "mock")

    // ------------------------------------------------------------------ L0

    @Test
    fun `打开空调走 L0 直达且不调用模型`() = runTest {
        var modelCalls = 0
        val throwingModel = object : ModelProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                modelCalls++
                throw IllegalStateException("L0 不得调用模型")
            }
        }
        val (workflow, adapter) = TestGraph.build(throwingModel)
        val result = workflow.process(input("req-l0", "打开空调"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertTrue(adapter.state.powerOn)
        assertEquals(0, modelCalls, "L0 直达不应调用模型")
        assertNotNull(result.executionPath)
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, result.executionPath!!.initialTier)
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, result.executionPath!!.finalTier)
        assertEquals(CandidateSource.L0_RULE, result.executionPath!!.candidateSource)
    }

    // ------------------------------------------------------------------ L1

    private fun ragOnGraph(
        stub: ModelProvider,
        registry: ToolRegistry,
        toolRetriever: ToolRetriever? = null,
        knowledgeRetriever: KnowledgeRetriever? = null
    ) = TestGraph.build(
        model = stub,
        toolCandidateProvider = RagToolCandidateProvider(
            registry,
            toolRetriever ?: HybridRuleToolRetriever(registry)
        ),
        ragRuntimeManager = RagRuntimeManager(
            FakeRagConfigRepository(
                RagRuntimeConfig(enabled = true, toolRagEnabled = true, knowledgeRagEnabled = true)
            ),
            toolRetriever = toolRetriever ?: HybridRuleToolRetriever(registry),
            knowledgeRetriever = knowledgeRetriever
        ),
        knowledgeRetriever = knowledgeRetriever
    )

    @Test
    fun `我有点冷走 L1 召回后由本地模型选择`() = runTest {
        val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
        val (workflow, adapter) = ragOnGraph(StubModelProvider(temperatureIncrease), registry)
        val result = workflow.process(input("req-l1", "我有点冷"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertEquals("climate.temperature_increase", result.executionResult!!.toolId)
        assertEquals(25.0, adapter.state.driverTemperature)
        assertNotNull(result.executionPath)
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, result.executionPath!!.finalTier)
        assertEquals(CandidateSource.L1_LOCAL_LLM, result.executionPath!!.candidateSource)
    }

    @Test
    fun `L1 模型输出候选集合外 Tool 被阻止 IVAI-TOOL-003`() = runTest {
        val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
        // "我有点冷" 仅召回 temperature_increase；模型却输出 power_on（集合外）→ 阻止。
        val (workflow, adapter) = ragOnGraph(StubModelProvider(powerOn), registry)
        val result = workflow.process(input("req-out", "我有点冷"), Session())

        assertEquals(AgentState.REJECTED, result.state)
        assertEquals("IVAI-TOOL-003", result.errorCode)
        assertNull(result.executionResult)
        assertTrue(!adapter.state.powerOn)
    }

    @Test
    fun `RAG 关闭时 L1 使用固定候选且不触碰检索器`() = runTest {
        val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
        var retrieverCalls = 0
        val throwingRetriever = object : ToolRetriever {
            override suspend fun retrieve(query: ToolRetrievalQuery, topK: Int): List<net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolCandidate> {
                retrieverCalls++
                throw IllegalStateException("RAG 关闭时不得调用检索器")
            }
        }
        val ragOff = RagRuntimeManager(
            FakeRagConfigRepository(), // enabled = false
            toolRetriever = throwingRetriever,
            knowledgeRetriever = null
        )
        val (workflow, adapter) = TestGraph.build(
            model = StubModelProvider(temperatureIncrease),
            toolCandidateProvider = RagToolCandidateProvider(registry, throwingRetriever),
            ragRuntimeManager = ragOff
        )
        val result = workflow.process(input("req-off", "我有点冷"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertEquals("climate.temperature_increase", result.executionResult!!.toolId)
        assertEquals(0, retrieverCalls, "RAG 关闭时不得访问检索器")
        assertTrue(adapter.state.driverTemperature == 25.0)
    }

    // ------------------------------------------------------------------ L2

    private class RawTextModelProvider(private val text: String) : ModelProvider {
        var calls = 0
        override suspend fun generate(request: ModelRequest): ModelResponse {
            calls++
            return ModelResponse(
                requestId = request.requestId,
                content = text,
                contentJson = null,
                model = "qwen3.5:4b",
                finishReason = "stop",
                latencyMs = 3
            )
        }
    }

    @Test
    fun `胎压报警走 L2 知识 RAG 返回带来源回答且不产生 Tool Call`() = runTest {
        val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
        val knowledgeRetriever: KnowledgeRetriever = KnowledgeRetrieverImpl(SampleKnowledgeDocs.chunks)
        val model = RawTextModelProvider("胎压报警指轮胎气压过低，请安全停车检查并补气。（来源：故障·胎压报警说明 v1.0）")
        val (workflow, adapter) = ragOnGraph(model, registry, knowledgeRetriever = knowledgeRetriever)
        val listener = CollectingAgentEventListener()
        val result = workflow.process(input("req-l2", "胎压报警是什么意思"), Session())

        assertEquals(AgentState.REPLY_READY, result.state)
        assertTrue(result.responseText.contains("胎压报警"))
        assertTrue(result.responseText.contains("来源"))
        assertNull(result.executionResult, "L2 不得产生 Tool Call")
        assertNull(adapter.state.lastExecution)
        assertNotNull(result.executionPath)
        assertEquals(IntentTier.L2_LOCAL_KNOWLEDGE, result.executionPath!!.finalTier)
        assertEquals(1, model.calls)
    }

    @Test
    fun `Knowledge RAG 不可用时不得生成无依据答案并转云`() = runTest {
        val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
        var modelCalls = 0
        val model = object : ModelProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                modelCalls++
                return ModelResponse(
                    requestId = request.requestId,
                    content = "胎压报警就是轮胎没气了",
                    contentJson = null,
                    model = "qwen3.5:4b",
                    finishReason = "stop",
                    latencyMs = 3
                )
            }
        }
        // RAG 打开但 Knowledge 检索器为空 → knowledge 不可用。
        val ragNoKnowledge = RagRuntimeManager(
            FakeRagConfigRepository(
                RagRuntimeConfig(enabled = true, toolRagEnabled = true, knowledgeRagEnabled = true)
            ),
            toolRetriever = HybridRuleToolRetriever(registry),
            knowledgeRetriever = null
        )
        val (workflow, _) = TestGraph.build(
            model = model,
            ragRuntimeManager = ragNoKnowledge,
            knowledgeRetriever = null
        )
        val result = workflow.process(input("req-l2b", "胎压报警是什么意思"), Session())

        // 不生成无依据的本地知识答案：转云（预留）。
        assertEquals(AgentState.CLOUD_REQUIRED, result.state)
        assertTrue(result.responseText.contains("云端"))
        assertEquals(0, modelCalls, "知识不可用时不得调用 LLM 编造答案")
        assertNotNull(result.executionPath)
        assertEquals(IntentTier.L3_CLOUD_AI, result.executionPath!!.finalTier)
        assertTrue(result.executionPath!!.transitions.any { it.from == IntentTier.L2_LOCAL_KNOWLEDGE && it.to == IntentTier.L3_CLOUD_AI })
    }

    @Test
    fun `L2 检索为空时不得生成确定性答案并转云`() = runTest {
        val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
        var modelCalls = 0
        val model = object : ModelProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                modelCalls++
                return ModelResponse(
                    requestId = request.requestId,
                    content = "没有相关说明",
                    contentJson = null,
                    model = "qwen3.5:4b",
                    finishReason = "stop",
                    latencyMs = 3
                )
            }
        }
        // 空知识库 → 检索为空。
        val emptyKnowledge = KnowledgeRetrieverImpl(emptyList())
        val rag = RagRuntimeManager(
            FakeRagConfigRepository(
                RagRuntimeConfig(enabled = true, toolRagEnabled = true, knowledgeRagEnabled = true)
            ),
            toolRetriever = HybridRuleToolRetriever(registry),
            knowledgeRetriever = emptyKnowledge
        )
        val (workflow, _) = TestGraph.build(
            model = model,
            ragRuntimeManager = rag,
            knowledgeRetriever = emptyKnowledge
        )
        val result = workflow.process(input("req-l2c", "胎压报警是什么意思"), Session())
        assertEquals(AgentState.CLOUD_REQUIRED, result.state)
        assertEquals(0, modelCalls)
        assertTrue(result.responseText.contains("未找到"))
    }
}
