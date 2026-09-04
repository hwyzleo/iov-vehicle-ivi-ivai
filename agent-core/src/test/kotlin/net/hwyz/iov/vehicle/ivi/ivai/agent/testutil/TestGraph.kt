package net.hwyz.iov.vehicle.ivi.ivai.agent.testutil

import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockClimateToolAdapter
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
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.observability.TelemetryRecorder
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.KnowledgeReranker
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
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
        softwareVersion: String? = null
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
        val tieredRouter = TieredIntentRouter(fastMatcher, domainClassifier)
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
            softwareVersion = softwareVersion
        )
        return workflow to adapter
    }
}
