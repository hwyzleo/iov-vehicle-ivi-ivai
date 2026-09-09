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
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.RuntimeEnvironment
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
     * CR-009/CR-010 集成装配：注册全部 160 个治理 Tool（Mock 桩）+ mock-governed 适配器
     * + 18 个 Capability Pack。与 AgentService.buildAgentGraph 使用**同一**
     * RuntimeCapabilityAssembler + 统一候选集（EARS #10）：开发桩模式（STUB）下
     * 白名单 DRAFT 仅经 Mock Adapter 参与 L0/L1 骨架验证，DRAFT 状态保留。
     */
    fun buildGovernedStubGraph(
        model: ModelProvider,
        eventListener: net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEventListener? = null,
        lifecycle: ToolLifecycleListener? = null,
        config: AgentConfig = AgentConfig(model = "qwen3.5:4b", ollamaBaseUrl = "http://localhost:11434"),
        vehicleModel: String? = null,
        softwareVersion: String? = null,
        environment: RuntimeEnvironment = GovernanceWorkspace.devStubEnvironment()
    ): Triple<AgentWorkflow, net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockGovernedToolAdapter, ToolRegistry> {
        val governedAdapter = net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockGovernedToolAdapter()
        // CR-010: 统一候选集 = 160 个 canonical 治理 Tool（旧 6 空调 ID 经 Alias 映射，不再单独注册）。
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
        // CR-010: 与 AgentService 共用同一 RuntimeCapabilityAssembler + 统一候选集。
        val capabilitySelector = CapabilityPackSelector.governed(
            catalog = GovernanceWorkspace.runtimePacks(),
            governanceCatalog = GovernanceWorkspace.catalog,
            defaultEnvironment = environment
        )
        val tieredRouter = TieredIntentRouter(
            fastMatcher, domainClassifier,
            domainRouter = DomainRouter(registry),
            capabilitySelector = capabilitySelector,
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

    /**
     * CR-016：治理桩图 + 终态快照捕获（供候选边界冻结 / 不变量校验断言）。
     */
    fun buildGovernedStubGraphWithSnapshot(
        model: ModelProvider,
        holder: SnapshotHolder
    ): AgentWorkflow {
        val (workflow, _, _) = buildGovernedStubGraph(
            model,
            eventListener = null
        )
        // 重新装配并注入 evaluationListener 捕获快照。
        val governedAdapter = net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockGovernedToolAdapter()
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        val validator = ToolValidator(registry)
        val agentPolicy = AgentPolicyEngine(registry, ToolPolicyEngine())
        val executor = DefaultToolExecutor(
            registry = registry,
            adapterRegistry = AdapterRegistry().register(governedAdapter),
            executionTimeoutMs = AgentConfig(model = "qwen3.5:4b", ollamaBaseUrl = "http://localhost:11434").executionTimeoutMs
        )
        val capabilitySelector = CapabilityPackSelector.governed(
            catalog = GovernanceWorkspace.runtimePacks(),
            governanceCatalog = GovernanceWorkspace.catalog,
            defaultEnvironment = GovernanceWorkspace.devStubEnvironment()
        )
        val tieredRouter = TieredIntentRouter(
            DefaultFastIntentMatcher(registry), DomainClassifier(registry),
            domainRouter = DomainRouter(registry),
            capabilitySelector = capabilitySelector,
            registry = registry
        )
        val config = AgentConfig(model = "qwen3.5:4b", ollamaBaseUrl = "http://localhost:11434")
        return AgentWorkflow(
            modelProvider = model,
            registry = registry,
            router = Router(),
            promptBuilder = PromptBuilder(registry),
            validator = validator,
            agentPolicy = agentPolicy,
            toolExecutor = executor,
            config = config,
            tieredRouter = tieredRouter,
            toolCandidateProvider = net.hwyz.iov.vehicle.ivi.ivai.agent.router.AllEnabledToolsProvider(registry),
            vehicleStateProvider = VehicleStateProvider { governedAdapter.snapshot() },
            evaluationListener = { holder.snapshot = it },
            vehicleModel = null,
            softwareVersion = null
        )
    }
}
