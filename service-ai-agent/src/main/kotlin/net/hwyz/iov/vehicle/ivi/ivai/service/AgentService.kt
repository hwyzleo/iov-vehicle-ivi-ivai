package net.hwyz.iov.vehicle.ivi.ivai.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockClimateToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockVehicleState
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptBuilder
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.Router
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentInput
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only session snapshot for the chat UI to reconcile on reconnection
 * (IVI-IVAI-DSN-CR-002). History entries are role + text pairs; message-level
 * dedup stays on the ViewModel side via its local messageId/requestId index.
 */
data class SessionSnapshot(
    val sessionId: String,
    val history: List<HistoryEntry>,
    val pendingConfirmationId: String?
) {
    data class HistoryEntry(val role: String, val text: String)
}

/**
 * Android service boundary (IVI-IVAI-DSN-CR-001 + CR-002):
 *  - owns the agent coroutine scope and the session
 *  - builds the full agent graph (Ollama provider + registry + validator + policy + executor + mock adapter)
 *  - exposes the [AgentEvent] stream + submit / confirm / cancel to app-demo
 *  - enforces one active turn per session (concurrency strategy of CR-002)
 *  - keeps agent-core Android-free
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = Session()

    /** One active turn per session; guards submit/confirm/cancel. */
    private val activeTurn = AtomicBoolean(false)

    private val _events = MutableSharedFlow<AgentEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Stable, user-facing agent events consumed by the chat UI. */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

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
     * Submits a user utterance as a new turn using the caller-provided [requestId]
     * (the ViewModel owns requestId/turnId/messageId per IVI-IVAI-DSN-CR-002) and
     * returns it. Returns null when another turn is already active (single-turn
     * policy); the caller should not start a new tool task in that case.
     */
    fun submit(
        text: String,
        turnId: String = UUID.randomUUID().toString(),
        requestId: String = UUID.randomUUID().toString(),
        source: String = "app"
    ): String? {
        if (!activeTurn.compareAndSet(false, true)) return null
        scope.launch {
            try {
                agent.process(
                    AgentInput(requestId = requestId, text = text, source = source, turnId = turnId),
                    session
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("submit failed: ${e.message}")
                _events.tryEmit(
                    AgentEvent.TurnFailed(
                        session.sessionId, turnId, requestId,
                        message = "处理请求时发生错误，请重试",
                        errorCode = "IVAI-EXEC-001", retryable = true
                    )
                )
            } finally {
                activeTurn.set(false)
            }
        }
        return requestId
    }

    /**
     * Approves a pending confirmation (idempotent at the workflow level).
     * Returns false when another turn is active.
     */
    fun confirm(confirmationId: String): Boolean {
        if (!activeTurn.compareAndSet(false, true)) return false
        scope.launch {
            try {
                agent.confirm(confirmationId, session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("confirm failed: ${e.message}")
            } finally {
                activeTurn.set(false)
            }
        }
        return true
    }

    /**
     * Cancels a pending confirmation (no tool is executed).
     * Returns false when another turn is active.
     */
    fun cancel(confirmationId: String): Boolean {
        if (!activeTurn.compareAndSet(false, true)) return false
        scope.launch {
            try {
                agent.cancel(confirmationId, session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("cancel failed: ${e.message}")
            } finally {
                activeTurn.set(false)
            }
        }
        return true
    }

    /** Snapshot used by the ViewModel to reconcile after service reconnection. */
    fun sessionSnapshot(): SessionSnapshot = SessionSnapshot(
        sessionId = session.sessionId,
        history = session.history().map { SessionSnapshot.HistoryEntry(role = it.role, text = it.content) },
        pendingConfirmationId = session.pendingConfirmationId
    )

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
            lifecycleListener = ToolLifecycleLogger { log(it) },
            eventListener = { event -> _events.tryEmit(event) }
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
