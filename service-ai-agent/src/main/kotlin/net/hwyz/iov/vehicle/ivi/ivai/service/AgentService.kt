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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockClimateToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockVehicleState
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptBuilder
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeManager
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DefaultFastIntentMatcher
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DomainClassifier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.RagToolCandidateProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.Router
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TieredIntentRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentInput
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentWorkflow
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProviderType
import net.hwyz.iov.vehicle.ivi.ivai.model.config.DefaultModelConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigState
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigValidator
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelRuntimeConfig
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.ModelProviderFactory
import net.hwyz.iov.vehicle.ivi.ivai.service.config.AndroidKeystoreSecretStore
import net.hwyz.iov.vehicle.ivi.ivai.service.config.DataStorePublicConfigStore
import net.hwyz.iov.vehicle.ivi.ivai.service.config.DataStoreRagConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.service.config.MaskingLoggingInterceptor
import net.hwyz.iov.vehicle.ivi.ivai.speech.android.AndroidSpeechRecognitionEngineFactory
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AndroidKeystoreAsrSecretStore
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrDataStorePublicConfigStore
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.DefaultAsrConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.observability.LoggingTelemetryRecorder
import net.hwyz.iov.vehicle.ivi.ivai.observability.ToolLifecycleLogger
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.LocalEmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.KnowledgeReranker
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.KnowledgeRetrieverImpl
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.SampleKnowledgeDocs
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.tool.HybridRuleToolRetriever
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.AdapterRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.DefaultToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Android service boundary (IVI-IVAI-DSN-CR-001 + CR-002 + CR-004):
 *  - owns the agent coroutine scope and the session
 *  - builds the full agent graph via [ModelProviderFactory] and rebuilds the
 *    provider when the runtime LLM config changes (no restart required)
 *  - implements the stable [AiAgentClient] contract for Chatbot / future ASR /
 *    other clients — clients never touch ModelProvider / ToolExecutor / Adapter
 *  - enforces one active turn per session (concurrency strategy of CR-002)
 *  - keeps agent-core Android-free
 */
class AgentService : Service(), AiAgentClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = Session()

    /** One active turn per session; guards submit/confirm/cancel. */
    private val activeTurn = AtomicBoolean(false)

    private val _events = MutableSharedFlow<AgentEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Stable, user-facing agent events consumed by clients. */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val agentRef = AtomicReference<AgentWorkflow?>()
    private val mockAdapterRef = AtomicReference<MockClimateToolAdapter?>()

    /** Single shared LLM runtime config repository (IVI-IVAI-DSN-CR-003). */
    lateinit var configRepository: ModelConfigRepository
        private set

    /** Single shared RAG runtime config repository (IVI-IVAI-DSN-CR-005). */
    lateinit var ragConfigRepository: RagConfigRepository
        private set

    /** Single shared ASR runtime config repository (IVI-IVAI-DSN-CR-006). */
    lateinit var asrConfigRepository: AsrConfigRepository
        private set

    /** Creates speech engines from an immutable config snapshot (CR-006). */
    lateinit var asrEngineFactory: AndroidSpeechRecognitionEngineFactory
        private set

    /** RAG runtime health (status for the settings page). */
    private var ragRuntimeManager: RagRuntimeManager? = null

    /** Single shared OkHttpClient (interceptor masks Authorization/api-key). */
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var providerFactory: ModelProviderFactory
    private var promptBuilderRef: PromptBuilder? = null

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        configRepository = buildConfigRepository()
        ragConfigRepository = DataStoreRagConfigRepository(this, scope)
        asrConfigRepository = DefaultAsrConfigRepository(
            publicStore = AsrDataStorePublicConfigStore(this),
            secretStore = AndroidKeystoreAsrSecretStore(this),
            scope = scope
        )
        asrEngineFactory = AndroidSpeechRecognitionEngineFactory(this)
        okHttpClient = OkHttpClient.Builder()
            .addInterceptor(MaskingLoggingInterceptor())
            .build()
        providerFactory = ModelProviderFactory(configRepository, okHttpClient)
        val initialBaseUrl = runCatching {
            runBlocking { configRepository.loadSnapshot().baseUrl.toString() }
        }.getOrDefault(BuildConfig.OLLAMA_BASE_URL)
        buildAgentGraph(initialBaseUrl)
        observeConfigChanges()
        log("AgentService created: baseUrl=$initialBaseUrl model=${BuildConfig.OLLAMA_MODEL}")
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        log("AgentService destroyed")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ AiAgentClient

    override suspend fun submit(command: AgentCommand): Boolean = when (command) {
        is AgentCommand.HandleText -> submitText(
            text = command.text,
            turnId = command.turnId,
            requestId = command.requestId,
            source = command.inputSource.name.lowercase()
        ) != null
        is AgentCommand.Confirm -> confirm(command.confirmationId)
        is AgentCommand.Cancel -> cancel(command.confirmationId)
    }

    override fun observeEvents(sessionId: String): Flow<AgentEvent> =
        events.filter { it.sessionId == sessionId }

    override suspend fun getSessionSnapshot(sessionId: String): AgentSessionSnapshot {
        val current = session.sessionId
        if (current.isEmpty() || current != sessionId || agentRef.get() == null) {
            throw ServiceException(ServiceErrorCode.SNAPSHOT_UNAVAILABLE, "Session Snapshot 不可用")
        }
        return AgentSessionSnapshot(
            sessionId = current,
            history = session.history().map { AgentSessionSnapshot.HistoryEntry(it.role, it.content) },
            pendingConfirmationId = session.pendingConfirmationId,
            activeTurnId = null,
            lastExecutionPath = session.lastExecutionPath
        )
    }

    // ------------------------------------------------------------------ legacy-friendly submit/confirm/cancel

    /**
     * Submits a user utterance as a new turn using the caller-provided [requestId]
     * and returns it; null when another turn is already active (single-turn
     * policy) or the service is not ready (IVAI-SERVICE-001).
     */
    fun submitText(
        text: String,
        turnId: String = UUID.randomUUID().toString(),
        requestId: String = UUID.randomUUID().toString(),
        source: String = "app"
    ): String? {
        val agent = agentRef.get() ?: return null
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
                        errorCode = ServiceErrorCode.SERVICE_UNAVAILABLE, retryable = true
                    )
                )
            } finally {
                activeTurn.set(false)
            }
        }
        return requestId
    }

    /** Alias of [submitText] kept for gateway wiring. */
    fun submit(
        text: String,
        turnId: String = UUID.randomUUID().toString(),
        requestId: String = UUID.randomUUID().toString(),
        source: String = "app"
    ): String? = submitText(text, turnId, requestId, source)

    /**
     * Approves a pending confirmation (idempotent at the workflow level).
     * Returns false when another turn is active.
     */
    fun confirm(confirmationId: String): Boolean {
        if (!activeTurn.compareAndSet(false, true)) return false
        scope.launch {
            try {
                agentRef.get()?.confirm(confirmationId, session)
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
                agentRef.get()?.cancel(confirmationId, session)
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

    // ------------------------------------------------------------------ diagnostics for the UI

    /** Read-only prompt template snapshot for the settings / debug page. */
    fun promptSnapshot(): PromptSnapshot? = promptBuilderRef?.snapshot()

    fun vehicleState(): MockVehicleState? = mockAdapterRef.get()?.state

    fun sessionId(): String = session.sessionId

    /** Device speech capability for the UI voice entry (CR-006). */
    fun asrCapability(): SpeechCapability = asrEngineFactory.capability()

    fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    inner class LocalBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    // ------------------------------------------------------------------ graph wiring

    /**
     * Observes the runtime config and rebuilds the provider (and therefore the
     * agent graph) when a new valid config is published, so a config save takes
     * effect for the next request without restarting the app (CR-003/CR-004).
     */
    private fun observeConfigChanges() {
        scope.launch {
            var seen = false
            configRepository.configState.collect { state ->
                if (state is ModelConfigState.Valid) {
                    if (seen) {
                        buildAgentGraph(state.config.baseUrl.toString())
                        log("Agent graph rebuilt on config change: ${state.config.baseUrl}")
                    } else {
                        seen = true
                    }
                }
            }
        }
    }

    private fun buildAgentGraph(initialBaseUrl: String) {
        val adapter = MockClimateToolAdapter()
        val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
        val validator = ToolValidator(registry)
        val promptBuilder = PromptBuilder(registry)
        val provider: ModelProvider = runCatching {
            runBlocking { providerFactory.create(configRepository.loadSnapshot()) }
        }.getOrElse {
            log("provider build fallback to Ollama: ${it.message}")
            providerFactory.create(
                ModelRuntimeConfig(
                    baseUrl = initialBaseUrl.toHttpUrlOrNull()
                        ?: throw IllegalStateException("invalid base url $initialBaseUrl"),
                    providerType = ModelProviderType.OLLAMA,
                    modelName = BuildConfig.OLLAMA_MODEL,
                    apiKey = null,
                    version = 0L
                )
            )
        }
        val executor = DefaultToolExecutor(
            registry = registry,
            adapterRegistry = AdapterRegistry().register(adapter),
            lifecycleListener = ToolLifecycleLogger { log(it) }
        )

        // CR-005: tiered routing + Tool/Knowledge RAG runtime.
        val fastMatcher = DefaultFastIntentMatcher(registry)
        val domainClassifier = DomainClassifier(registry)
        val tieredRouter = TieredIntentRouter(fastMatcher, domainClassifier)
        val toolRetriever = HybridRuleToolRetriever(registry)
        val toolCandidateProvider = RagToolCandidateProvider(registry, toolRetriever)
        val knowledgeRetriever: KnowledgeRetriever = KnowledgeRetrieverImpl(SampleKnowledgeDocs.chunks)
        val embeddingProvider = LocalEmbeddingProvider()
        val ragManager = RagRuntimeManager(
            repository = ragConfigRepository,
            toolRetriever = toolRetriever,
            knowledgeRetriever = knowledgeRetriever,
            embeddingProvider = embeddingProvider
        )
        ragRuntimeManager = ragManager

        val workflow = AgentWorkflow(
            modelProvider = provider,
            registry = registry,
            router = Router(),
            promptBuilder = promptBuilder,
            validator = validator,
            agentPolicy = AgentPolicyEngine(registry, ToolPolicyEngine()),
            toolExecutor = executor,
            config = AgentConfig(model = BuildConfig.OLLAMA_MODEL, ollamaBaseUrl = initialBaseUrl),
            tieredRouter = tieredRouter,
            toolCandidateProvider = toolCandidateProvider,
            vehicleStateProvider = VehicleStateProvider { adapter.snapshot() },
            telemetryRecorder = LoggingTelemetryRecorder { log(it) },
            lifecycleListener = ToolLifecycleLogger { log(it) },
            eventListener = { event -> _events.tryEmit(event) },
            ragRuntimeManager = ragManager,
            knowledgeRetriever = knowledgeRetriever,
            knowledgeReranker = KnowledgeReranker(),
            vehicleModel = VEHICLE_MODEL,
            softwareVersion = SOFTWARE_VERSION
        )
        promptBuilderRef = promptBuilder
        mockAdapterRef.set(adapter)
        agentRef.set(workflow)
    }

    /** RAG runtime health for the settings page (CR-005). */
    fun ragRuntimeStatus(): RagRuntimeStatus = ragRuntimeManager?.status() ?: RagRuntimeStatus.DISABLED

    private fun buildConfigRepository(): ModelConfigRepository =
        DefaultModelConfigRepository(
            publicStore = DataStorePublicConfigStore(this),
            secretStore = AndroidKeystoreSecretStore(this),
            defaultBaseUrl = BuildConfig.OLLAMA_BASE_URL,
            defaultModelName = BuildConfig.OLLAMA_MODEL,
            // 与 network_security_config / 地址校验联动：debug 允许局域网 HTTP，release 要求 HTTPS。
            validator = ModelConfigValidator(allowInsecureHttp = BuildConfig.ALLOW_INSECURE_HTTP),
            scope = scope
        )

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "IVI-IVAI"

        /** Mock vehicle deployment metadata (CR-005 availability filtering). */
        val VEHICLE_MODEL: String? = null
        const val SOFTWARE_VERSION = "0.1.0"
    }
}
