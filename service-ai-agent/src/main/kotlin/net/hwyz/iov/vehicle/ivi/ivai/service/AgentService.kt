package net.hwyz.iov.vehicle.ivi.ivai.service

import android.app.Service
import android.content.Context
import java.io.File
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockClimateToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockGovernedToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockVehicleState
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptBuilder
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.EmbeddingProviderFactory
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigState
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeManager
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DefaultFastIntentMatcher
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.DomainClassifier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.RagToolCandidateProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.Router
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TieredIntentRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehicleDomainInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehicleFeatureSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehiclePackInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehicleToolInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehicleWorkflowInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentInput
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentWorkflow
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.WorkflowRuntime
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.WorkflowValidator
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProviderType
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretStore
import net.hwyz.iov.vehicle.ivi.ivai.model.config.DefaultModelConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigState
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigValidator
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelRuntimeConfig
import net.hwyz.iov.vehicle.ivi.ivai.model.provider.ModelProviderFactory
import net.hwyz.iov.vehicle.ivi.ivai.service.config.AndroidKeystoreEmbeddingSecretStore
import net.hwyz.iov.vehicle.ivi.ivai.service.config.AndroidKeystoreSecretStore
import net.hwyz.iov.vehicle.ivi.ivai.service.config.DataStorePublicConfigStore
import net.hwyz.iov.vehicle.ivi.ivai.service.config.DataStoreRagConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.service.config.MaskingLoggingInterceptor
import net.hwyz.iov.vehicle.ivi.ivai.speech.android.AndroidSpeechRecognitionEngineFactory
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AndroidKeystoreAsrSecretStore
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrDataStorePublicConfigStore
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrProviderType
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.DefaultAsrConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.observability.LoggingTelemetryRecorder
import net.hwyz.iov.vehicle.ivi.ivai.observability.ToolLifecycleLogger
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.LocalEmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.KnowledgeReranker
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.KnowledgeRetrieverImpl
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.SampleKnowledgeDocs
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.KnowledgeRagRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.ToolRagRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.ToolRetrievalDocumentBuilder
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.toIndexedDocument
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.DistanceMetric
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.FileVectorPersistence
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.IndexBuildRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.IndexLifecycle
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.LocalExactVectorStore
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.tool.HybridRuleToolRetriever
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.DefaultRuntimeCapabilityAssembler
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceRuntimeMode
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.RuntimeEnvironment
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ToolAliasCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.AdapterRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.DefaultToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
class AgentService : Service(), AiAgentClient, AgentTestSupport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session = Session()

    /** One active turn per session; guards submit/confirm/cancel. */
    private val activeTurn = AtomicBoolean(false)

    /** CR-012: 当前活动请求的 Job 与 requestId（供测试超时取消）。 */
    private val activeJobRef = AtomicReference<Job?>(null)
    private val activeRequestIdRef = AtomicReference<String?>(null)

    /** CR-012: 按 requestId 汇聚的测试快照（仅 debug/test 写入；有界避免膨胀）。 */
    private val evaluationSnapshots = ConcurrentHashMap<String, AgentEvaluationSnapshot>()

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

    /** Embedding Provider API key（CR-011：鉴权配置使用 Keystore 引用，不存明文）。 */
    private lateinit var embeddingSecretStore: SecretStore

    /** 最近一次构建 Agent 图所用的 Base URL（RAG 配置变更重建时复用）。 */
    private var lastAgentBaseUrl: String = BuildConfig.OLLAMA_BASE_URL

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
        embeddingSecretStore = AndroidKeystoreEmbeddingSecretStore(this)
        ragConfigRepository = DataStoreRagConfigRepository(
            this,
            scope,
            embeddingSecretStore = embeddingSecretStore,
            allowInsecureHttp = BuildConfig.ALLOW_INSECURE_HTTP
        )
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
        observeRagConfigChanges()
        log("AgentService created: baseUrl=$initialBaseUrl model=${BuildConfig.OLLAMA_MODEL}")
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Explicit start is only used to keep the Session alive across Activity
        // lifecycle (ChatActivity.onStart). Never auto-restart after a process
        // kill: a freshly recreated service would own a brand-new empty Session,
        // silently wiping the conversation the user already had.
        return START_NOT_STICKY
    }

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
        // CR-012: 提交时捕获 Session，避免测试超时后 createTestSession 替换
        // Session 导致在途请求用到新上下文（用例隔离）。
        val sessionAtSubmit = session
        scope.launch {
            activeRequestIdRef.set(requestId)
            activeJobRef.set(coroutineContext[Job])
            try {
                agent.process(
                    AgentInput(requestId = requestId, text = text, source = source, turnId = turnId),
                    sessionAtSubmit
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("submit failed: ${e.message}")
                _events.tryEmit(
                    AgentEvent.TurnFailed(
                        sessionAtSubmit.sessionId, turnId, requestId,
                        message = "处理请求时发生错误，请重试",
                        errorCode = ServiceErrorCode.SERVICE_UNAVAILABLE, retryable = true
                    )
                )
            } finally {
                activeRequestIdRef.compareAndSet(requestId, null)
                activeJobRef.set(null)
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

    /**
     * 本车功能只读快照：治理目录中的领域 / 能力包 / 工具 / 工作流
     * （中文名称 + 稳定代码），供设置页「本车功能」只读展示（CR-009）。
     */
    fun vehicleFeatureSnapshot(): VehicleFeatureSnapshot = VehicleFeatureSnapshot(
        baselineVersion = GovernanceWorkspace.baseline.baselineVersion,
        sourceCatalogVersion = GovernanceWorkspace.baseline.sourceCatalogVersion,
        domains = BusinessDomainId.entries.map { VehicleDomainInfo(code = it.code, label = it.label) },
        packs = GovernanceWorkspace.packs.map {
            VehiclePackInfo(
                packId = it.packId,
                name = it.name,
                domainCode = it.domainId.code,
                toolTarget = it.toolTarget,
                workflowTarget = it.workflowTarget,
                priority = it.priority.label
            )
        },
        tools = GovernanceWorkspace.tools.map {
            VehicleToolInfo(
                toolId = it.toolId,
                name = it.name,
                domainCode = it.domainId.code,
                packId = it.capabilityPackId,
                operationType = it.operationType.name
            )
        },
        workflows = GovernanceWorkspace.workflows.map {
            VehicleWorkflowInfo(
                workflowId = it.workflowId,
                name = it.name,
                ownerDomainCode = it.ownerDomainId.code,
                domainCodes = it.domainIds.map { d -> d.code },
                stepToolIds = it.stepToolIds,
                failurePolicy = it.failurePolicy
            )
        }
    )

    fun sessionId(): String = session.sessionId

    // ------------------------------------------------------------------ CR-012 测试能力（仅 debug/test）

    /** CR-012: 批次执行环境门禁（见 [AgentTestSupport]）。 */
    override fun isTestEnvironmentAllowed(): Boolean = BuildConfig.DEBUG

    /** CR-012: 创建隔离的测试 Session（见 [AgentTestSupport]）。 */
    override fun createTestSession(): String {
        if (!BuildConfig.DEBUG) return session.sessionId
        if (activeTurn.get()) return session.sessionId
        session = Session()
        return session.sessionId
    }

    /** CR-012: 取消活动请求（见 [AgentTestSupport]）。 */
    override fun cancelRequest(requestId: String): Boolean {
        if (activeRequestIdRef.get() != requestId) return false
        activeJobRef.get()?.cancel()
        return true
    }

    /**
     * CR-012 串行语义加固：等待活动 Turn 释放（终态事件 ≠ process 返回）。
     * Runner 提交下一条前调用；超时返回 false。
     */
    override suspend fun awaitIdle(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        while (activeTurn.get()) {
            if (System.nanoTime() >= deadline) return false
            delay(AWAIT_IDLE_POLL_MS)
        }
        return true
    }

    /** CR-012: 强制取消当前活动请求（不校验 requestId；阻塞调用未中断时的兜底）。 */
    override fun cancelActiveRequest(): Boolean {
        activeJobRef.get()?.cancel()
        return true
    }

    /** CR-012: 按 requestId 读取测试快照（见 [AgentTestSupport]）。 */
    override fun evaluationSnapshot(requestId: String): AgentEvaluationSnapshot? {
        if (!BuildConfig.DEBUG) return null
        return evaluationSnapshots[requestId]
    }

    /**
     * Speech capability for the UI voice entry (CR-006 + CR-007). When the
     * configured provider is HTTP_COMPATIBLE the entry stays enabled even on
     * devices without a Recognition Service (remote engine); VENDOR is not yet
     * implemented so the entry is disabled until a future CR lands.
     */
    fun asrCapability(): SpeechCapability {
        val provider = runBlocking {
            runCatching { asrConfigRepository.loadSnapshot().public.providerType }.getOrNull()
        }
        return when (provider) {
            AsrProviderType.HTTP_COMPATIBLE -> SpeechCapability.REMOTE
            AsrProviderType.VENDOR -> SpeechCapability.UNAVAILABLE
            else -> asrEngineFactory.capability()
        }
    }

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

    /**
     * CR-011 补齐：RAG 配置（含 Embedding Provider）变化时重建 Agent 图，使新配置
     * 对下一个请求立即生效（与 ModelConfig 热更新同一模式）。Embedding 模型切换由
     * IndexLifecycle 按兼容键触发全量重建（IVAI-REQ-102）。
     */
    private fun observeRagConfigChanges() {
        scope.launch {
            var seen = false
            ragConfigRepository.configState.collect { state ->
                if (state is RagConfigState.Valid) {
                    if (seen) {
                        buildAgentGraph(lastAgentBaseUrl)
                        log("Agent graph rebuilt on RAG config change (version=${state.config.version})")
                    } else {
                        seen = true
                    }
                }
            }
        }
    }

    private fun buildAgentGraph(initialBaseUrl: String) {
        lastAgentBaseUrl = initialBaseUrl
        val adapter = MockClimateToolAdapter()
        val governedAdapter = MockGovernedToolAdapter()
        // CR-010: 运行时统一候选集 = 全部 160 个治理 Tool（canonical）。6 个旧空调 ID
        // 不再作为独立可执行定义，而是经 ToolAliasCatalog 映射到 canonical Tool。
        val registry = GovernanceWorkspace.registerAllStubs(ToolRegistry())
        // CR-010: 单测、集成测试与 AgentService 使用同一 RuntimeCapabilityAssembler。
        //   - Release（STRICT）：统一候选集只含 APPROVED + enabled + 唯一有效 Binding；
        //     当前全部 DRAFT → 无候选，硬动作按 IVAI-CAP-001 拒绝。
        //   - Debug（DEVELOPMENT_STUB）：白名单 DRAFT 仅经 Mock Adapter 参与 L0/L1 骨架验证。
        val runtimeEnvironment = if (BuildConfig.DEBUG) {
            GovernanceWorkspace.devStubEnvironment()
        } else {
            GovernanceWorkspace.assertReleaseReady(RuntimeEnvironment(mode = GovernanceRuntimeMode.STRICT))
            RuntimeEnvironment(mode = GovernanceRuntimeMode.STRICT)
        }
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
            adapterRegistry = AdapterRegistry()
                .register(adapter)
                .register(governedAdapter),
            lifecycleListener = ToolLifecycleLogger { log(it) }
        )

        // CR-005: tiered routing + Tool/Knowledge RAG runtime.
        val fastMatcher = DefaultFastIntentMatcher(registry)
        val domainClassifier = DomainClassifier(registry)
        // CR-008: 领域预路由 + 能力包选择 + Workflow 运行时（P0 启用）。
        val domainRouter = DomainRouter(registry)
        // CR-010: 使用共享 RuntimeCapabilityAssembler 装配统一候选集（与单测一致）。
        val capabilitySelector = CapabilityPackSelector.governed(
            catalog = GovernanceWorkspace.runtimePacks(),
            governanceCatalog = GovernanceWorkspace.catalog,
            defaultEnvironment = runtimeEnvironment
        )
        val tieredRouter = TieredIntentRouter(
            fastMatcher, domainClassifier,
            domainRouter = domainRouter,
            capabilitySelector = capabilitySelector,
            registry = registry
        )
        val agentPolicy = AgentPolicyEngine(registry, ToolPolicyEngine())
        val workflowRuntime = WorkflowRuntime(
            registry = registry,
            toolValidator = validator,
            agentPolicy = agentPolicy,
            toolExecutor = executor,
            workflowValidator = WorkflowValidator(registry),
            vehicleStateProvider = VehicleStateProvider { adapter.snapshot() }
        )
        // CR-011: 端侧双路径 RAG（LOCAL_EXACT VectorStore + EmbeddingProvider + Reranker NONE）。
        // 索引构建失败时回退 CR-005 规则/关键字检索，保证服务可用。
        val ragStack = runCatching { buildCr011RagStack(registry, runtimeEnvironment) }.getOrNull()
        if (ragStack == null) log("RAG 向量栈构建失败，回退 CR-005 规则检索")
        val toolRetriever = ragStack?.toolRetriever ?: HybridRuleToolRetriever(registry)
        val toolCandidateProvider = RagToolCandidateProvider(registry, toolRetriever)
        val knowledgeRetriever: KnowledgeRetriever = ragStack?.knowledgeRetriever
            ?: KnowledgeRetrieverImpl(SampleKnowledgeDocs.chunks)
        val embeddingProvider = ragStack?.embedding ?: LocalEmbeddingProvider()
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
            agentPolicy = agentPolicy,
            toolExecutor = executor,
            config = AgentConfig(model = BuildConfig.OLLAMA_MODEL, ollamaBaseUrl = initialBaseUrl),
            tieredRouter = tieredRouter,
            toolCandidateProvider = toolCandidateProvider,
            vehicleStateProvider = VehicleStateProvider { adapter.snapshot() },
            telemetryRecorder = LoggingTelemetryRecorder { log(it) },
            lifecycleListener = ToolLifecycleLogger { log(it) },
            eventListener = { event -> _events.tryEmit(event) },
            // CR-012: 测试快照仅在 debug/test 写入有界存储；普通聊天不消费。
            evaluationListener = { snapshot ->
                if (BuildConfig.DEBUG) {
                    evaluationSnapshots[snapshot.requestId] = snapshot
                    if (evaluationSnapshots.size > MAX_EVALUATION_SNAPSHOTS) {
                        evaluationSnapshots.keys.firstOrNull()
                            ?.let { evaluationSnapshots.remove(it) }
                    }
                }
            },
            ragRuntimeManager = ragManager,
            knowledgeRetriever = knowledgeRetriever,
            knowledgeReranker = KnowledgeReranker(),
            vehicleModel = VEHICLE_MODEL,
            softwareVersion = SOFTWARE_VERSION,
            workflowRuntime = workflowRuntime
        )
        promptBuilderRef = promptBuilder
        mockAdapterRef.set(adapter)
        agentRef.set(workflow)
    }

    /** RAG runtime health for the settings page (CR-005). */
    fun ragRuntimeStatus(): RagRuntimeStatus = ragRuntimeManager?.status() ?: RagRuntimeStatus.DISABLED

    /**
     * CR-011 端侧双路径 RAG 栈：LOCAL_EXACT VectorStore + EmbeddingProvider +
     * Reranker NONE。tool-intent 与 knowledge 使用独立命名空间/文档集/开关。
     * 首期 Embedding 使用哈希桩（在线 HTTP Embedding 接入由 RagConfig 配置驱动，
     * 后续 CR 接入配置 UI 后切换）。索引构建失败抛异常，由调用方回退。
     */
    private fun buildCr011RagStack(
        registry: ToolRegistry,
        environment: RuntimeEnvironment
    ): Cr011RagStack = runBlocking {
        // CR-011 补齐：Embedding Provider 由持久化 RagConfig.embedding 驱动（IVAI-REQ-097
        // 不得在业务代码中硬编码供应商）；配置为空 → 回退本地哈希桩。
        val embeddingConfig = ragConfigRepository.loadSnapshot().rag.embedding
        val embedding = EmbeddingProviderFactory.create(
            config = embeddingConfig,
            allowInsecureHttp = BuildConfig.ALLOW_INSECURE_HTTP,
            credentialResolver = { runCatching { embeddingSecretStore.read() }.getOrNull() }
        )
        val indexDir = File(getDir("rag_index", MODE_PRIVATE), "v1")
        val persistence = FileVectorPersistence(indexDir)
        val store = LocalExactVectorStore(persistence)
        val lifecycle = IndexLifecycle(store, persistence, embedding)

        // L1: 从治理目录 + 统一候选集构建 tool-intent 索引。
        val assembler = DefaultRuntimeCapabilityAssembler()
        val runtimeSet = assembler.assemble(
            catalog = GovernanceWorkspace.catalog,
            aliases = ToolAliasCatalog,
            environment = environment.copy(
                selectedPackIds = GovernanceWorkspace.runtimePacks().map { it.packId }.toSet()
            )
        )
        val toolDocs = ToolRetrievalDocumentBuilder(
            registry,
            WorkflowRegistry,
            deterministicCatalog = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.DeterministicIntentCatalog.build()
        )
            .buildAll(GovernanceWorkspace.catalog, runtimeSet, sourceVersion = SOFTWARE_VERSION)
        lifecycle.ensureIndex(
            IndexBuildRequest(
                namespace = ToolRagRetriever.DEFAULT_NAMESPACE,
                documents = toolDocs.map { it.toIndexedDocument() },
                providerType = embedding.descriptor.providerType,
                modelId = embedding.descriptor.modelId,
                modelVersion = embedding.descriptor.modelVersion,
                dimension = embedding.descriptor.dimension,
                distanceMetric = DistanceMetric.COSINE,
                // CR-017: 语义载荷升级（位置 Alias/enum/正反例/冲突集入文档）→ 文档构建版本提升，
                // 兼容键变化强制旧索引失效并全量重建（REQ-177：索引未刷新不得宣称新 Alias 生效）。
                documentBuilderVersion = "tool-builder-2",
                governanceVersion = runtimeSet.governanceVersion,
                indexVersion = "v1"
            )
        )

        // L2: 从批准知识语料构建 knowledge 索引（首期内置样例）。
        val knowledgeChunks = SampleKnowledgeDocs.chunks
        lifecycle.ensureIndex(
            IndexBuildRequest(
                namespace = KnowledgeRagRetriever.DEFAULT_NAMESPACE,
                documents = knowledgeChunks.map { it.toIndexedDocument() },
                providerType = embedding.descriptor.providerType,
                modelId = embedding.descriptor.modelId,
                modelVersion = embedding.descriptor.modelVersion,
                dimension = embedding.descriptor.dimension,
                distanceMetric = DistanceMetric.COSINE,
                documentBuilderVersion = "knowledge-builder-1",
                governanceVersion = "ivai-governance-v1",
                indexVersion = "v1"
            )
        )

        val byChunkId = knowledgeChunks.associateBy { it.chunkId }
        Cr011RagStack(
            embedding = embedding,
            toolRetriever = ToolRagRetriever(registry, store, embedding),
            knowledgeRetriever = KnowledgeRagRetriever(store, embedding, chunkResolver = { byChunkId[it] })
        )
    }

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

        /** CR-012: 测试快照有界容量（防止调试会话长期运行导致内存膨胀）。 */
        const val MAX_EVALUATION_SNAPSHOTS = 512

        /** CR-012: awaitIdle 轮询间隔。 */
        const val AWAIT_IDLE_POLL_MS = 20L

        const val NANOS_PER_MILLI = 1_000_000L

        /** Mock vehicle deployment metadata (CR-005 availability filtering). */
        val VEHICLE_MODEL: String? = null
        const val SOFTWARE_VERSION = "0.1.0"
    }
}

/** CR-011 双路径 RAG 栈装配结果（索引构建失败时由调用方回退 CR-005 检索）。 */
private data class Cr011RagStack(
    val embedding: net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingProvider,
    val toolRetriever: net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetriever,
    val knowledgeRetriever: net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetriever
)
