package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockClimateToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.StubModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.TestGraph
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-008 集成测试（领域预路由 + 能力包 + Workflow 端到端）：
 *  - 「打开露营模式」→ WORKFLOW_EXECUTION → 多步骤执行（power_on + set_temp）。
 *  - 「打开充电设置」→ 能源领域 + NAVIGATE_UI → 无可用包 → IVAI-CAP-001。
 *  - 「查看空调状态」→ 包内 L0 直达 + CR-008 可观测字段。
 *  - 「我有点冷」→ 领域限定 L1 由本地模型选择。
 *  - 候选受控：包外候选不得进入 Prompt / 执行。
 */
class AgentWorkflowCr008Test {

    private val temperatureIncrease = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.temperature_increase","functionId":"AC_Temperature_2","arguments":{"position":"driver","step":2}}],"modelConfidence":0.9,"riskLevel":"medium","needConfirmation":false,"missingArguments":[],"reasonCode":"IMPLICIT_COLD_INTENT"}"""

    private fun input(requestId: String, text: String) =
        AgentInput(requestId = requestId, text = text, source = "mock")

    /** 装配启用了 CR-008（领域路由 + 能力包 + Workflow 运行时）的完整图。 */
    private fun buildCr008Graph(model: net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider) =
        TestGraph.build(
            model = model,
            domainRouter = DomainRouter(ClimateToolDefinitions.registerAll(net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry())),
            capabilitySelector = CapabilityPackSelector(),
            workflowRuntime = null // 在测试内按需单独注入
        )

    @Test
    fun `打开露营模式走 Workflow 多步骤执行`() = runTest {
        val registry = ClimateToolDefinitions.registerAll(net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry())
        val adapter = MockClimateToolAdapter()
        val runtime = buildRuntime(registry, adapter)
        val (workflow, _) = TestGraph.build(
            model = StubModelProvider(temperatureIncrease),
            adapter = adapter,
            domainRouter = DomainRouter(registry),
            capabilitySelector = CapabilityPackSelector(),
            workflowRuntime = runtime
        )
        val result = workflow.process(input("req-wf", "打开露营模式"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertTrue(adapter.state.powerOn, "露营模式应先开启空调")
        assertEquals(26.0, adapter.state.driverTemperature, "露营模式应设定温度 26℃")
        assertNotNull(result.executionPath)
        assertEquals(IntentTier.WORKFLOW_EXECUTION, result.executionPath!!.finalTier)
        assertNull(result.errorCode)
        // CR-008 可观测字段。
        val debug = result.executionPath
        assertTrue(debug!!.finalReasonCode == net.hwyz.iov.vehicle.ivi.ivai.agent.router.RouteReasonCode.WORKFLOW_MATCHED)
    }

    @Test
    fun `打开充电设置因无可用能力包被拒绝 IVAI-CAP-001`() = runTest {
        val (workflow, adapter) = buildCr008Graph(StubModelProvider(temperatureIncrease))
        val result = workflow.process(input("req-cap", "打开充电设置"), Session())

        assertEquals(AgentState.REJECTED, result.state)
        assertEquals("IVAI-CAP-001", result.errorCode)
        assertNull(result.executionResult)
        assertNull(adapter.state.lastExecution)
        // 领域被识别为能源 + NAVIGATE_UI（可观测），但 P0 无能源包。
        assertEquals(IntentTier.REJECT, result.executionPath!!.finalTier)
    }

    @Test
    fun `查看空调状态走包内 L0 直达且模型不被调用`() = runTest {
        var modelCalls = 0
        val throwingModel = object : net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider {
            override suspend fun generate(request: net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest): net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse {
                modelCalls++
                throw IllegalStateException("L0 不得调用模型")
            }
        }
        val (workflow, adapter) = buildCr008Graph(throwingModel)
        val result = workflow.process(input("req-l0", "查看空调状态"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertEquals("climate.status_query", result.executionResult!!.toolId)
        assertEquals(0, modelCalls)
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, result.executionPath!!.finalTier)
        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSource.L0_RULE, result.executionPath!!.candidateSource)
        assertNotNull(adapter.state.lastExecution)
    }

    @Test
    fun `我有点冷走领域限定 L1 由本地模型选择`() = runTest {
        val registry = ClimateToolDefinitions.registerAll(net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry())
        val (workflow, adapter) = TestGraph.build(
            model = StubModelProvider(temperatureIncrease),
            domainRouter = DomainRouter(registry),
            capabilitySelector = CapabilityPackSelector()
        )
        val result = workflow.process(input("req-l1", "我有点冷"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertEquals("climate.temperature_increase", result.executionResult!!.toolId)
        // step=2 → 24 + 2 = 26
        assertEquals(26.0, adapter.state.driverTemperature)
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, result.executionPath!!.finalTier)
        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSource.L1_LOCAL_LLM, result.executionPath!!.candidateSource)
    }

    @Test
    fun `包外候选被过滤且模型选择包外 Tool 被阻止`() = runTest {
        val registry = ClimateToolDefinitions.registerAll(net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry())
        // 模型输出 seat.heat（包外、且不在注册表）。
        val model = StubModelProvider(
            """{"route":"LOCAL_TOOL","intents":[{"toolId":"seat.heat","arguments":{"position":"driver"}}],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"EXPLICIT_INTENT"}"""
        )
        // 自定义候选提供器：返回包外 Tool seat.heat（应被 pack 过滤）。
        val outOfPackProvider = object : net.hwyz.iov.vehicle.ivi.ivai.agent.router.ToolCandidateProvider {
            override suspend fun candidates(
                input: net.hwyz.iov.vehicle.ivi.ivai.agent.router.NormalizedInput,
                context: net.hwyz.iov.vehicle.ivi.ivai.agent.router.AgentContext,
                ragSnapshot: net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagExecutionSnapshot,
                capabilitySnapshot: net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilitySnapshot?
            ): net.hwyz.iov.vehicle.ivi.ivai.agent.router.ToolCandidateSet {
                val summary = net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolDefinitionSummary(
                    toolId = "seat.heat", name = "座椅加热", description = "test",
                    parameterSchema = "{}"
                )
                return net.hwyz.iov.vehicle.ivi.ivai.agent.router.ToolCandidateSet(
                    candidates = listOf(net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolCandidate("seat.heat", 5.0, listOf("enabled"), summary)),
                    source = net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSetSource.RETRIEVED,
                    topK = 1
                )
            }
        }
        val (workflow, _) = TestGraph.build(
            model = model,
            domainRouter = DomainRouter(registry),
            capabilitySelector = CapabilityPackSelector(),
            toolCandidateProvider = outOfPackProvider
        )
        val result = workflow.process(input("req-scope", "打开座椅加热"), Session())

        // seat.heat 不在 cabin.climate 包内 → 候选被过滤为空 → 模型选择包外 Tool。
        assertEquals(AgentState.REJECTED, result.state)
        assertEquals("IVAI-TOOL-001", result.errorCode)
    }

    private fun buildRuntime(
        registry: net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry,
        adapter: MockClimateToolAdapter
    ): WorkflowRuntime {
        val validator = net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator(registry)
        val agentPolicy = net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine(
            registry, net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolPolicyEngine()
        )
        val executor = net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.DefaultToolExecutor(
            registry = registry,
            adapterRegistry = net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.AdapterRegistry().register(adapter)
        )
        return WorkflowRuntime(
            registry = registry,
            toolValidator = validator,
            agentPolicy = agentPolicy,
            toolExecutor = executor,
            workflowValidator = WorkflowValidator(registry),
            vehicleStateProvider = net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider { adapter.snapshot() }
        )
    }
}
