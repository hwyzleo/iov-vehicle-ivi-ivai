package net.hwyz.iov.vehicle.ivi.ivai.agent.testutil

import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockClimateToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptBuilder
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.Router
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentWorkflow
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.IdempotencyGuard
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.observability.TelemetryRecorder
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
 * Builds a fully wired workflow against the mock adapter for tests.
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
        eventListener: net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEventListener? = null
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
        val workflow = AgentWorkflow(
            modelProvider = model,
            registry = registry,
            router = Router(),
            promptBuilder = PromptBuilder(registry),
            validator = validator,
            agentPolicy = agentPolicy,
            toolExecutor = executor,
            config = config,
            vehicleStateProvider = VehicleStateProvider { adapter.snapshot() },
            telemetryRecorder = telemetry,
            lifecycleListener = lifecycle,
            idempotencyGuard = idempotencyGuard,
            eventListener = eventListener
        )
        return workflow to adapter
    }
}
