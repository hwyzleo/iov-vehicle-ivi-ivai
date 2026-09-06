package net.hwyz.iov.vehicle.ivi.ivai.agent.testutil

import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockClimateToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptBuilder
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeManager
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DefaultFastIntentMatcher
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DomainClassifier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.Router
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TieredIntentRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.ToolCandidateProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentWorkflow
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.IdempotencyGuard
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.WorkflowRuntime
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.WorkflowValidator
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.observability.TelemetryRecorder
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.KnowledgeReranker
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.AdapterRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.DefaultToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolLifecycleListener
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider

/**
 * Builds a fully wired workflow against the mock adapter for tests (CR-005:
 * tiered routing + tool candidate provider + RAG runtime).
 */
object TestGraph {

    fun build(
        model: ModelProvider,
        telemetry: TelemetryRecorder? = null,
        lifecycle: ToolLifecycleListener? = null,
        config: AgentConfig = AgentConfig(model = "qwen3.5:4b", ollamaBaseUrl = "http://localhost:11434"),
        adapter: MockClimateToolAdapter = MockClimateToolAdapter(),
        idempotencyGuard: IdempotencyGuard = IdempotencyGuard(),
        toolExecutor: ToolExecutor? = null,
        eventListener: net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEventListener? = null,
        toolCandidateProvider: ToolCandidateProvider? = null,
        ragConfigRepository: RagConfigRepository? = null,
        toolRetriever: net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetriever? = null,
        knowledgeRetriever: KnowledgeRetriever? = null,
        ragRuntimeManager: RagRuntimeManager? = null,
        vehicleModel: String? = null,
        softwareVersion: String? = null,
        // CR-008 (default off → legacy behavior):
        domainRouter: DomainRouter? = null,
        capabilitySelector: CapabilityPackSelector? = null,
        workflowRuntime: WorkflowRuntime? = null
    ): Pair<AgentWorkflow, MockClimateToolAdapter> {
        val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
        val validator = ToolValidator(registry)
        val agentPolicy = AgentPolicyEngine(registry, ToolPolicyEngine())
        val executor = toolExecutor ?: DefaultToolExecutor(
            registry = registry,
            adapterRegistry = AdapterRegistry().register(adapter),
            lifecycleListener = lifecycle,
            executionTimeoutMs = config.executionTimeoutMs
        )
        val promptBuilder = PromptBuilder(registry)
        val fastMatcher = DefaultFastIntentMatcher(registry)
        val domainClassifier = DomainClassifier(registry)
        val tieredRouter = TieredIntentRouter(
            fastMatcher, domainClassifier,
            domainRouter = domainRouter,
            capabilitySelector = capabilitySelector,
            registry = registry
        )
        val candidateProvider = toolCandidateProvider
            ?: net.hwyz.iov.vehicle.ivi.ivai.agent.router.AllEnabledToolsProvider(registry)
        val ragManager = ragRuntimeManager
        val workflow = AgentWorkflow(
            modelProvider = model,
            registry = registry,
            router = Router(),
            promptBuilder = promptBuilder,
            validator = validator,
            agentPolicy = agentPolicy,
            toolExecutor = executor,
            config = config,
            tieredRouter = tieredRouter,
            toolCandidateProvider = candidateProvider,
            vehicleStateProvider = VehicleStateProvider { adapter.snapshot() },
            telemetryRecorder = telemetry,
            lifecycleListener = lifecycle,
            idempotencyGuard = idempotencyGuard,
            eventListener = eventListener,
            ragRuntimeManager = ragManager,
            knowledgeRetriever = knowledgeRetriever,
            knowledgeReranker = if (knowledgeRetriever != null) KnowledgeReranker() else null,
            vehicleModel = vehicleModel,
            softwareVersion = softwareVersion,
            workflowRuntime = workflowRuntime
        )
        return workflow to adapter
    }

    /**
     * CR-009 集成装配：注册全部 160 个治理 Tool（Mock 桩）+ mock-governed 适配器
     * + 18 个桩启用 Capability Pack。与 AgentService.buildAgentGraph 的 CR-009 装配一致，
     * 用于验证 160 个 Tool 的运行时链路（路由→校验→Policy→执行）全部可调通。
     */
    fun buildGovernedStubGraph(
        model: ModelProvider,
        eventListener: net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEventListener? = null,
        lifecycle: ToolLifecycleListener? = null,
        config: AgentConfig = AgentConfig(model = "qwen3.5:4b", ollamaBaseUrl = "http://localhost:11434"),
        vehicleModel: String? = null,
        softwareVersion: String? = null
    ): Triple<AgentWorkflow, net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockGovernedToolAdapter, ToolRegistry> {
        val governedAdapter = net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockGovernedToolAdapter()
        // 160 个治理 Tool（Mock 桩）注册进运行时；保留 6 个空调 P0 验证集后仍可叠加，
        // 此处直接以 160 治理桩为准（与 AgentService 装配一致）。
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        val validator = ToolValidator(registry)
        val agentPolicy = AgentPolicyEngine(registry, ToolPolicyEngine())
        val executor = DefaultToolExecutor(
            registry = registry,
            adapterRegistry = AdapterRegistry().register(governedAdapter),
            lifecycleListener = lifecycle,
            executionTimeoutMs = config.executionTimeoutMs
        )
        val promptBuilder = PromptBuilder(registry)
        val fastMatcher = DefaultFastIntentMatcher(registry)
        val domainClassifier = DomainClassifier(registry)
        val tieredRouter = TieredIntentRouter(
            fastMatcher, domainClassifier,
            domainRouter = DomainRouter(registry),
            capabilitySelector = CapabilityPackSelector(GovernanceWorkspace.runtimePacks()),
            registry = registry
        )
        val workflow = AgentWorkflow(
            modelProvider = model,
            registry = registry,
            router = Router(),
            promptBuilder = promptBuilder,
            validator = validator,
            agentPolicy = agentPolicy,
            toolExecutor = executor,
            config = config,
            tieredRouter = tieredRouter,
            toolCandidateProvider = net.hwyz.iov.vehicle.ivi.ivai.agent.router.AllEnabledToolsProvider(registry),
            vehicleStateProvider = VehicleStateProvider { governedAdapter.snapshot() },
            lifecycleListener = lifecycle,
            eventListener = eventListener,
            vehicleModel = vehicleModel,
            softwareVersion = softwareVersion
        )
        return Triple(workflow, governedAdapter, registry)
    }
}
