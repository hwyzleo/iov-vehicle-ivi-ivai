package net.hwyz.iov.vehicle.ivi.ivai.agenttest

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockGovernedToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptBuilder
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DefaultFastIntentMatcher
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DomainClassifier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.Router
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TieredIntentRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentInput
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentWorkflow
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.AdapterRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.DefaultToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * CR-019 workflow 终态集成测试：温度语义在真实 AgentWorkflow 链路的业务 Outcome。
 *
 *  - “主驾温度调高1度” → L0 adjust 执行（SUCCEEDED / EXECUTE）；
 *  - “温度调到35度” → 越界拒绝（REJECTED / IVAI-TEMP-RANGE-001，不执行）；
 *  - “温度调到” → 歧义追问（NEED_DIALOGUE / IVAI-TEMP-SEMANTIC-001，不执行）。
 */
class AgentWorkflowCr019IntegrationTest {

    private class QueuedModelProvider(vararg contents: String) : ModelProvider {
        private val queue = ArrayDeque(contents.toList())
        override suspend fun generate(request: ModelRequest): ModelResponse {
            val content = queue.removeFirst()
            return ModelResponse(
                requestId = request.requestId,
                content = content,
                contentJson = Json.parseToJsonElement(content),
                model = "stub",
                finishReason = "stop",
                latencyMs = 1
            )
        }
    }

    private val dialogueMissingTemp =
        """{"route":"LOCAL_DIALOGUE","intents":[{"toolId":"climate.temperature.set","arguments":{"zone":"driver"}}],"modelConfidence":0.85,"riskLevel":"medium","needConfirmation":false,"missingArguments":["temperature"],"reasonCode":"MISSING_SLOT"}"""

    private class SnapshotHolderLocal {
        var snapshot: AgentEvaluationSnapshot? = null
    }

    private fun buildWorkflow(model: ModelProvider, holder: SnapshotHolderLocal): AgentWorkflow {
        val governedAdapter = MockGovernedToolAdapter()
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        val tieredRouter = TieredIntentRouter(
            DefaultFastIntentMatcher(registry), DomainClassifier(registry),
            domainRouter = DomainRouter(registry),
            capabilitySelector = CapabilityPackSelector.governed(
                catalog = GovernanceWorkspace.runtimePacks(),
                governanceCatalog = GovernanceWorkspace.catalog,
                defaultEnvironment = GovernanceWorkspace.devStubEnvironment()
            ),
            registry = registry
        )
        return AgentWorkflow(
            modelProvider = model,
            registry = registry,
            router = Router(),
            promptBuilder = PromptBuilder(registry),
            validator = ToolValidator(registry),
            agentPolicy = AgentPolicyEngine(registry, ToolPolicyEngine()),
            toolExecutor = DefaultToolExecutor(
                registry = registry,
                adapterRegistry = AdapterRegistry().register(governedAdapter),
                executionTimeoutMs = 5_000
            ),
            config = AgentConfig(model = "qwen3.5:4b", ollamaBaseUrl = "http://localhost:11434"),
            tieredRouter = tieredRouter,
            toolCandidateProvider = net.hwyz.iov.vehicle.ivi.ivai.agent.router.AllEnabledToolsProvider(registry),
            vehicleStateProvider = VehicleStateProvider { governedAdapter.snapshot() },
            evaluationListener = { holder.snapshot = it },
            softwareVersion = "0.1.0",
            vehicleModel = null
        )
    }

    private fun input(requestId: String, text: String) = AgentInput(
        requestId = requestId,
        text = text,
        turnId = "t"
    )

    @Test
    fun `相对调温 L0 adjust 执行成功且业务 Outcome 为 EXECUTE`() = runTest {
        val holder = SnapshotHolderLocal()
        val workflow = buildWorkflow(QueuedModelProvider(), holder)
        val result = workflow.process(input("req-1", "主驾温度调高1度"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertEquals("climate.temperature.adjust", result.executionResult!!.toolId)
        val snapshot = holder.snapshot!!
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, snapshot.finalTier)
        assertEquals(EvaluationTerminalStatus.SUCCEEDED, snapshot.terminalStatus)
        assertEquals("driver", snapshot.normalizedArguments!!["zone"]!!.toString().trim('"'))
    }

    @Test
    fun `越界绝对温度拒绝且不执行`() = runTest {
        val holder = SnapshotHolderLocal()
        val workflow = buildWorkflow(QueuedModelProvider(), holder)
        val result = workflow.process(input("req-2", "温度调到35度"), Session())

        assertEquals(AgentState.REJECTED, result.state)
        assertNull(result.executionResult, "越界请求不得执行")
        val snapshot = holder.snapshot!!
        assertEquals(EvaluationTerminalStatus.REJECTED, snapshot.terminalStatus)
        assertEquals("IVAI-TEMP-RANGE-001", snapshot.reasonCode)
        // 处理路径 L1（越界在 L1 业务层直接拒绝），Tier 评分与业务终态 REJECTED 分别计算。
        assertEquals(IntentTier.L1_LOCAL_TOOL_REASONING, snapshot.finalTier)
    }

    @Test
    fun `歧义请求追问且不执行`() = runTest {
        val holder = SnapshotHolderLocal()
        val workflow = buildWorkflow(QueuedModelProvider(dialogueMissingTemp), holder)
        val result = workflow.process(input("req-3", "温度调到"), Session())

        assertEquals(
            AgentState.WAITING_USER, result.state,
            "state=${result.state} reasonCode=${holder.snapshot?.reasonCode} " +
                "finalTier=${holder.snapshot?.finalTier} stage=${holder.snapshot?.terminalStage} " +
                "failure=${holder.snapshot?.failureReason}"
        )
        assertNull(result.executionResult, "歧义请求不得执行")
        val snapshot = holder.snapshot!!
        assertEquals(EvaluationTerminalStatus.NEED_DIALOGUE, snapshot.terminalStatus)
        assertEquals("IVAI-TEMP-SEMANTIC-001", snapshot.reasonCode)
    }
}
