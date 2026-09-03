package net.hwyz.iov.vehicle.ivi.ivai.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockClimateToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockVehicleState
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptBuilder
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.Router
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentInput
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentResult
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentWorkflow
import net.hwyz.iov.vehicle.ivi.ivai.model.OllamaConfig
import net.hwyz.iov.vehicle.ivi.ivai.model.OllamaModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.observability.LoggingTelemetryRecorder
import net.hwyz.iov.vehicle.ivi.ivai.observability.ToolLifecycleLogger
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.AdapterRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.DefaultToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider
import java.util.UUID

/**
 * Android service boundary (IVI-IVAI-DSN-CR-001):
 *  - owns the agent coroutine scope and the session
 *  - builds the full agent graph (Ollama provider + registry + validator + policy + executor + mock adapter)
 *  - exposes IPC-ready binder to app-demo
 *  - keeps agent-core Android-free
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = Session()

    lateinit var agent: AgentWorkflow
        private set

    lateinit var mockAdapter: MockClimateToolAdapter
        private set

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val (workflow, adapter) = buildAgentGraph()
        agent = workflow
        mockAdapter = adapter
        log("AgentService created: baseUrl=${BuildConfig.OLLAMA_BASE_URL} model=${BuildConfig.OLLAMA_MODEL}")
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        log("AgentService destroyed")
        super.onDestroy()
    }

    /**
     * Submits a user utterance for processing on the service-owned coroutine scope.
     */
    fun submit(text: String, source: String = "app"): Deferred<AgentResult> {
        val requestId = UUID.randomUUID().toString()
        return scope.async {
            agent.process(AgentInput(requestId = requestId, text = text, source = source), session)
        }
    }

    fun vehicleState(): MockVehicleState = mockAdapter.state

    fun sessionId(): String = session.sessionId

    fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    inner class LocalBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    private fun buildAgentGraph(): Pair<AgentWorkflow, MockClimateToolAdapter> {
        val adapter = MockClimateToolAdapter()
        val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
        val validator = ToolValidator(registry)
        val provider = OllamaModelProvider(
            OllamaConfig(baseUrl = BuildConfig.OLLAMA_BASE_URL, model = BuildConfig.OLLAMA_MODEL)
        )
        val executor = DefaultToolExecutor(
            registry = registry,
            adapterRegistry = AdapterRegistry().register(adapter),
            lifecycleListener = ToolLifecycleLogger { log(it) }
        )
        val workflow = AgentWorkflow(
            modelProvider = provider,
            registry = registry,
            router = Router(),
            promptBuilder = PromptBuilder(registry),
            validator = validator,
            agentPolicy = AgentPolicyEngine(registry, ToolPolicyEngine()),
            toolExecutor = executor,
            config = AgentConfig(model = BuildConfig.OLLAMA_MODEL, ollamaBaseUrl = BuildConfig.OLLAMA_BASE_URL),
            vehicleStateProvider = VehicleStateProvider { adapter.snapshot() },
            telemetryRecorder = LoggingTelemetryRecorder { log(it) },
            lifecycleListener = ToolLifecycleLogger { log(it) }
        )
        return workflow to adapter
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "IVI-IVAI"
    }
}
