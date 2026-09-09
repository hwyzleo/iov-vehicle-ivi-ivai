package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilitySnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.candidate.FrozenCandidateSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainCandidate
import net.hwyz.iov.vehicle.ivi.ivai.agent.execution.AgentExecutionState
import net.hwyz.iov.vehicle.ivi.ivai.agent.execution.DefaultExecutionInvariantValidator
import net.hwyz.iov.vehicle.ivi.ivai.agent.execution.ExecutionInvariantValidator
import net.hwyz.iov.vehicle.ivi.ivai.agent.execution.InvariantResult
import net.hwyz.iov.vehicle.ivi.ivai.agent.execution.TerminalStage
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouteDecision
import net.hwyz.iov.vehicle.ivi.ivai.agent.error.ErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.ActualTarget
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationSnapshotProjector
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.SnapshotFacts
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.TargetType
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEventListener
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentExecutionPath
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.Cr008DebugInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.ParsedIntentSummary
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.ParsedOutputSummary
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.TierTransition
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.ToolDebugInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.TurnDebugInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentOutput
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentRoute
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.Intent
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.jsonArgsToValues
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.valuesToJsonArgs
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.PolicyOutcome
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptBuilder
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagExecutionInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagExecutionSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeManager
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.AgentContext
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSetSource
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSource
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.RouteReasonCode
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TieredIntentRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TieredRouteDecision
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.ToolCallCandidate
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.ToolCandidateProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.ToolCandidateSet
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TextNormalizer
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.PendingTask
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.error.Cr016ErrorCodes
import net.hwyz.iov.vehicle.ivi.ivai.model.AgentPerformanceMetrics
import net.hwyz.iov.vehicle.ivi.ivai.model.ChatMessage
import net.hwyz.iov.vehicle.ivi.ivai.model.HttpNetworkMetrics
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelClientException
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelErrorKind
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.model.PerformanceMetricsValidator
import net.hwyz.iov.vehicle.ivi.ivai.model.ProviderComputeMetrics
import net.hwyz.iov.vehicle.ivi.ivai.model.StreamingModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.lifecycle.ModelCallOutcome
import net.hwyz.iov.vehicle.ivi.ivai.model.lifecycle.ModelRequestLifecycle
import net.hwyz.iov.vehicle.ivi.ivai.model.lifecycle.ModelTimeoutPolicy
import net.hwyz.iov.vehicle.ivi.ivai.model.parsing.ConstrainedModelResponseParser
import net.hwyz.iov.vehicle.ivi.ivai.model.parsing.ParsedModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.observability.RequestTelemetry
import net.hwyz.iov.vehicle.ivi.ivai.observability.TelemetryRecord
import net.hwyz.iov.vehicle.ivi.ivai.observability.TelemetryRecorder
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeChunk
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.RetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.KnowledgeCitationMapper
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge.KnowledgeReranker
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.CanonicalAliasLexicon
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.DefaultAliasLexicons
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.PositionAliasLexicon
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.PositionAliasResolver
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr017ErrorCodes
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr019ErrorCodes
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ToolCatalogV1
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.WorkflowCatalogV1
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema.CanonicalizationResult
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema.CanonicalizationSource
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema.DefaultParameterCanonicalizationService
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema.ParameterCanonicalizationService
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionContext
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutionResult
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolLifecycleEvent
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolLifecycleListener
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolLifecyclePhase
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidationIssue
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ValidationIssueKind
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateSnapshot

data class AgentInput(
    val requestId: String,
    val text: String,
    val source: String = "unknown",
    val turnId: String = requestId,
    /** Monotonic submission instant; [AgentPerformanceMetrics.endToEndMs] starts here. */
    val submittedAtNs: Long = System.nanoTime()
)

/**
 * Result of processing one user turn.
 *
 * @param state final state machine state
 * @param rawModelContent raw message.content from the model (for debugging display)
 * @param replayed true when an idempotent duplicate request returned a cached result
 */
data class AgentResult(
    val requestId: String,
    val sessionId: String,
    val state: AgentState,
    val route: AgentRoute? = null,
    val parsedOutput: AgentOutput? = null,
    val validationIssues: List<ToolValidationIssue> = emptyList(),
    val executionResult: ToolExecutionResult? = null,
    val errorCode: String? = null,
    val responseText: String,
    val rawModelContent: String? = null,
    val replayed: Boolean = false,
    val performance: AgentPerformanceMetrics? = null,
    val telemetry: RequestTelemetry,
    val executionPath: AgentExecutionPath? = null
)

/**
 * Accumulates non-overlapping top-level phase durations for one turn
 * (IVI-IVAI-DSN-CR-004). All values in milliseconds from a monotonic clock.
 * [network] / [providerCompute] are diagnostic sub-metrics inside modelCallTotal
 * and are never re-added.
 */
private class TurnTimings(val submittedAtNs: Long) {
    var queueMs: Long? = null
    var contextAndPromptMs: Long? = null
    var modelCallTotalMs: Long? = null
    var network: HttpNetworkMetrics? = null
    var providerCompute: ProviderComputeMetrics? = null
    var parseAndSchemaMs: Long? = null
    var routeAndPolicyMs: Long? = null
    var toolExecutionMs: Long? = null
    var eventDispatchMs: Long? = null
    var timeToFirstTokenMs: Long? = null
    var streamingUsed: Boolean? = null
    var endToEndMs: Long = 0

    fun addRouteAndPolicy(durationMs: Long) {
        routeAndPolicyMs = (routeAndPolicyMs ?: 0L) + durationMs
    }
}

/**
 * Tracks the tier execution path for one turn (CR-005): initial tier, final
 * tier, transitions and candidate source. Rendered into AgentEvents and the
 * debug payload.
 */
private class ExecutionPathTracker(initialTier: IntentTier) {
    var initialTier: IntentTier = initialTier
        private set
    var currentTier: IntentTier = initialTier
        private set
    var finalReasonCode: String = ""
    var candidateSource: CandidateSource? = null
    private val transitions = mutableListOf<TierTransition>()

    fun transition(to: IntentTier, reasonCode: String) {
        if (currentTier != to) {
            transitions += TierTransition(from = currentTier, to = to, reasonCode = reasonCode)
            currentTier = to
        }
    }

    fun build(): AgentExecutionPath = AgentExecutionPath(
        initialTier = initialTier,
        finalTier = currentTier,
        transitions = transitions.toList(),
        finalReasonCode = finalReasonCode,
        candidateSource = candidateSource
    )
}

/**
 * CR-012: 本轮执行候选跟踪（领域 / 能力包 / 目标 / 规范化参数）。终态时投影为
 * [AgentEvaluationSnapshot]；目标与参数在最终合法候选阶段（Schema 默认值 /
 * Alias 预置 / 单位规范化后、实际执行前）冻结，执行失败不影响已确定的匹配事实。
 */
private class CandidateTrace {
    var initialDomains: List<DomainCandidate> = emptyList()
    var finalDomain: BusinessDomainId? = null
    var selectedPackIds: Set<String> = emptySet()
    var selectedTarget: ActualTarget? = null
    var normalizedArguments: JsonObject? = null
    var governanceVersion: String? = null
    var candidateVersion: String? = null

    /** CR-017: L1 是否执行过检索（RAG 命中或回退）。 */
    var retrievalInvoked: Boolean? = null

    /** CR-017: L1 检索后、包过滤收敛后的候选数（供快照导出）。 */
    var retrievedCandidateCount: Int? = null

    /** CR-018: L1 候选 Top-K canonical ID（RAG 或固定候选，供诊断导出）。 */
    var retrievedCandidateIds: List<String> = emptyList()

    /** CR-018: L1 候选 Top-K 最终分数（与 retrievedCandidateIds 对齐）。 */
    var retrievedCandidateScores: List<Double> = emptyList()

    /** CR-017: 是否发起过模型分发（= llmInvoked，供快照导出）。 */
    var modelDispatchAttempted: Boolean? = null

    /** CR-016：候选边界通过后的不可变冻结快照（执行失败不得清空）。 */
    var frozenSnapshot: FrozenCandidateSnapshot? = null
        private set

    fun reset(): CandidateTrace {
        initialDomains = emptyList()
        finalDomain = null
        selectedPackIds = emptySet()
        selectedTarget = null
        normalizedArguments = null
        governanceVersion = null
        candidateVersion = null
        frozenSnapshot = null
        retrievalInvoked = null
        retrievedCandidateCount = null
        retrievedCandidateIds = emptyList()
        retrievedCandidateScores = emptyList()
        modelDispatchAttempted = null
        return this
    }

    /**
     * CR-016：候选边界通过后冻结 canonical Target/Arguments。
     * 后续 Policy/Confirmation/Adapter 失败只能更新终态和错误，不得清空快照。
     */
    fun freeze(
        type: TargetType,
        targetId: String,
        canonicalArguments: JsonObject,
        candidateSetHash: String,
        source: net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSource,
        matchedRuleIds: List<String>,
        argumentSources: Map<String, String>
    ) {
        selectedTarget = ActualTarget(type, targetId)
        normalizedArguments = canonicalArguments
        frozenSnapshot = FrozenCandidateSnapshot(
            type = type,
            targetId = targetId,
            canonicalArguments = canonicalArguments,
            candidateSetHash = candidateSetHash,
            source = source,
            matchedRuleIds = matchedRuleIds,
            argumentSources = argumentSources.mapValues { (_, v) ->
                net.hwyz.iov.vehicle.ivi.ivai.agent.router.deterministic.ArgumentSource.valueOf(v)
            }
        )
    }

    /** 从路由决策捕获领域 / 能力包事实。 */
    fun capture(decision: TieredRouteDecision) {
        val domain = decision.domain
        initialDomains = domain?.candidates ?: emptyList()
        finalDomain = domain?.topDomain
        val snapshot = decision.capabilitySnapshot
        selectedPackIds = snapshot?.selectedPackIds ?: emptySet()
        governanceVersion = snapshot?.governanceVersion
        candidateVersion = null
    }
}

/**
 * Agent orchestration engine: drives the state machine from RECEIVED to a
 * terminal state, enforcing tiered intent routing (CR-005):
 *
 *  RECEIVED → NORMALIZED → TIER_ROUTED
 *    ├─ L0_MATCHED → CANDIDATE_READY → unified execution chain (no RAG/LLM)
 *    ├─ L1_RETRIEVING_TOOLS → MODEL_REQUESTED → PARSED → CANDIDATE_READY → chain
 *    ├─ L2_RETRIEVING_KNOWLEDGE → MODEL_REQUESTED → REPLY (natural language)
 *    ├─ L3_CLOUD_REQUIRED
 *    └─ REJECTED
 *
 * Every tool candidate — L0 rule, L1 local LLM or L3 cloud — passes the SAME
 * validation / policy / confirmation / idempotency / execution chain.
 */
class AgentWorkflow(
    private val modelProvider: ModelProvider,
    private val registry: ToolRegistry,
    private val router: net.hwyz.iov.vehicle.ivi.ivai.agent.router.Router,
    private val promptBuilder: PromptBuilder,
    private val validator: ToolValidator,
    private val agentPolicy: AgentPolicyEngine,
    private val toolExecutor: ToolExecutor,
    private val config: AgentConfig,
    private val tieredRouter: TieredIntentRouter,
    private val toolCandidateProvider: ToolCandidateProvider,
    private val vehicleStateProvider: VehicleStateProvider? = null,
    private val telemetryRecorder: TelemetryRecorder? = null,
    private val lifecycleListener: ToolLifecycleListener? = null,
    private val idempotencyGuard: IdempotencyGuard = IdempotencyGuard(),
    private val eventListener: AgentEventListener? = null,
    /** CR-012: 测试快照回调（仅 debug/test 接线消费；普通聊天不增加内部字段）。 */
    private val evaluationListener: ((AgentEvaluationSnapshot) -> Unit)? = null,
    private val ragRuntimeManager: RagRuntimeManager? = null,
    private val knowledgeRetriever: KnowledgeRetriever? = null,
    private val knowledgeReranker: KnowledgeReranker? = null,
    private val vehicleModel: String? = null,
    private val softwareVersion: String? = null,
    /** CR-008: 已注册 Workflow 的运行时（首期内部实现），注入后支持 WORKFLOW_EXECUTION。 */
    private val workflowRuntime: WorkflowRuntime? = null,
    /** CR-016: 共享版本化 Alias Lexicon（L0/L1/Validator/Scorer 同一实例）。 */
    private val aliasLexicon: CanonicalAliasLexicon = DefaultAliasLexicons.DEFAULT,
    /** CR-017: 位置 Alias 解析器（车型拓扑 + 歧义保护），L1 证据 / 候选边界 / Prompt 同源。 */
    private val positionResolver: PositionAliasResolver = PositionAliasResolver(),
    /**
     * CR-016: 共享参数规范化服务（L0/L1/Validator/Scorer 同一实例）。
     * 不注入时在 init 中基于 [registry] 构建，保证 L1 候选边界使用完整注册表。
     */
    private val canonicalizer: ParameterCanonicalizationService? = null,
    /** CR-016: 模型请求生命周期（分阶段超时 + 清理屏障）。 */
    private val modelRequestLifecycle: ModelRequestLifecycle = ModelRequestLifecycle(
        ModelTimeoutPolicy(totalTimeoutMs = AgentConfig.DEFAULT_REQUEST_TIMEOUT_MS)
    )
) {

    /** 实际使用的参数规范化服务（CR-016 共享实例）。 */
    private val effectiveCanonicalizer: ParameterCanonicalizationService =
        canonicalizer ?: DefaultParameterCanonicalizationService(registry, aliasLexicon)

    /** CR-016: 执行状态不变量校验器（终态封口时验证）。 */
    private val invariantValidator: ExecutionInvariantValidator = DefaultExecutionInvariantValidator()

    /** CR-016: 当前 Turn 执行状态跟踪（单活动 Turn 串行，共享实例安全）。 */
    private var executionState: AgentExecutionState = AgentExecutionState("")

    /** CR-016: 本轮 LLM 是否已发起（callModel 记录）。 */
    private var llmInvokedThisTurn: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    /** CR-012: 本轮候选跟踪（单活动 Turn 串行执行，共享实例安全）。 */
    private val candidateTrace = CandidateTrace()

    suspend fun process(input: AgentInput, session: Session): AgentResult {
        candidateTrace.reset()
        // CR-016: 每轮创建独立执行状态跟踪；终态封口时校验不变量。
        executionState = AgentExecutionState(requestId = input.requestId)
        llmInvokedThisTurn = false
        val turnStartNs = System.nanoTime()
        val timings = TurnTimings(input.submittedAtNs)
        timings.queueMs = msBetween(input.submittedAtNs, turnStartNs)

        record(input, session, AgentState.RECEIVED)
        session.appendUser(input.text)
        emit(AgentEvent.UserSubmitted(session.sessionId, input.turnId, input.requestId, input.text))
        emit(AgentEvent.ProcessingStarted(session.sessionId, input.turnId, input.requestId))

        // --- confirmation approval path (skip the model) ---
        val pending = session.pendingTask
        if (pending != null && pending.needConfirmation && input.text.trim() == config.confirmationKeyword) {
            session.consumePendingTask()
            val execPath = pending.executionPath?.copy(
                finalTier = IntentTier.L1_LOCAL_TOOL_REASONING,
                candidateSource = pending.executionPath.candidateSource ?: CandidateSource.L1_LOCAL_LLM
            )
            return executeAuthorized(
                input, session, listOf(pending.intent), parsedOutput = null, timings,
                executionPath = execPath
            )
        }

        // --- tiered routing ---
        val ragSnapshot = ragRuntimeManager?.snapshot() ?: RagExecutionSnapshot.DEFAULT
        record(input, session, AgentState.NORMALIZED)
        val normalized = TextNormalizer.normalize(input.text)
        val context = AgentContext(
            requestId = input.requestId,
            sessionId = session.sessionId,
            source = input.source,
            vehicleModel = vehicleModel,
            softwareVersion = softwareVersion,
            vehicleState = vehicleState()
        )
        record(input, session, AgentState.TIER_ROUTED)
        val pendingMissingArgs = session.pendingTask?.takeIf { !it.needConfirmation }
        val decision = if (pendingMissingArgs != null) {
            // Follow-up on a missing-argument task → tool reasoning regardless of domain.
            TieredRouteDecision(
                tier = IntentTier.L1_LOCAL_TOOL_REASONING,
                confidence = 0.8,
                reasonCode = RouteReasonCode.L1_TOOL_DOMAIN,
                retrievalQuery = RetrievalQuery.Tools(
                    ToolRetrievalQuery(
                        text = normalized.normalized,
                        vehicleModel = vehicleModel,
                        softwareVersion = softwareVersion
                    )
                )
            )
        } else {
            tieredRouter.route(normalized, context)
        }

        // CR-012: 记录本轮领域 / 能力包事实，供终态快照投影。
        candidateTrace.capture(decision)

        return when (decision.tier) {
            IntentTier.L0_DETERMINISTIC_TOOL ->
                handleL0(input, session, decision, timings)
            IntentTier.L1_LOCAL_TOOL_REASONING ->
                handleL1(input, session, decision, normalized, context, timings, ragSnapshot)
            IntentTier.L2_LOCAL_KNOWLEDGE ->
                handleL2(input, session, decision, context, timings, ragSnapshot)
            IntentTier.L3_CLOUD_AI ->
                handleL3(input, session, decision, timings)
            IntentTier.WORKFLOW_EXECUTION ->
                handleWorkflow(input, session, decision, timings)
            IntentTier.REJECT ->
                handleReject(input, session, decision, timings)
        }
    }

    // ------------------------------------------------------------------ tier branches

    /** L0 deterministic path: no RAG, no LLM — straight into the unified chain. */
    private suspend fun handleL0(
        input: AgentInput,
        session: Session,
        decision: TieredRouteDecision,
        timings: TurnTimings
    ): AgentResult {
        val candidate = decision.directCandidate ?: return handleReject(input, session, decision, timings)
        record(input, session, AgentState.L0_MATCHED)
        record(input, session, AgentState.CANDIDATE_READY)
        val tracker = ExecutionPathTracker(IntentTier.L0_DETERMINISTIC_TOOL)
        tracker.finalReasonCode = decision.reasonCode
        tracker.candidateSource = CandidateSource.L0_RULE
        return executeCandidate(
            input, session, candidate, tracker, timings, rawModelContent = null,
            cr008 = cr008Of(decision)
        )
    }

    /**
     * L1 path: Tool/Intent retrieval → local LLM → unified chain. When RAG is
     * off (or degraded) the candidate provider returns the fixed enabled set;
     * the LLM must select strictly within the recalled Top-K.
     */
    private suspend fun handleL1(
        input: AgentInput,
        session: Session,
        decision: TieredRouteDecision,
        normalized: net.hwyz.iov.vehicle.ivi.ivai.agent.router.NormalizedInput,
        context: AgentContext,
        timings: TurnTimings,
        ragSnapshot: RagExecutionSnapshot
    ): AgentResult {
        val initialTier = if (decision.reasonCode == RouteReasonCode.L0_RULE_AMBIGUOUS ||
            decision.reasonCode == RouteReasonCode.L0_MISSING_ARGUMENTS
        ) {
            IntentTier.L0_DETERMINISTIC_TOOL
        } else {
            IntentTier.L1_LOCAL_TOOL_REASONING
        }
        val tracker = ExecutionPathTracker(initialTier)
        if (initialTier == IntentTier.L0_DETERMINISTIC_TOOL) {
            tracker.transition(IntentTier.L1_LOCAL_TOOL_REASONING, decision.reasonCode)
        }
        tracker.finalReasonCode = decision.reasonCode
        val cr008 = cr008Of(decision)

        // L0 missing-argument fast path: no model call, ask directly.
        if (decision.missingToolId != null && decision.missingArguments.isNotEmpty()) {
            return handleMissingArgumentsDialogue(
                input, session, decision.missingToolId, decision.missingArguments, tracker, timings, cr008
            )
        }

        // CR-019：温度绝对越界在路由层已判定（IVAI-TEMP-RANGE-001）→ L1 直接拒绝
        // （不检索、不调模型、不形成候选），业务终态 REJECTED；处理路径保持 L1
        // （Tier 评分按处理层级、Outcome 按业务终态分别计算）。
        if (decision.reasonCode == Cr019ErrorCodes.TEMP_RANGE) {
            tracker.finalReasonCode = decision.reasonCode
            return turnFailed(
                input, session, timings, decision.reasonCode,
                "目标温度超出当前车型允许范围，已拒绝。", false, tracker, null,
                "绝对温度越界（IVAI-TEMP-RANGE-001）", state = AgentState.REJECTED, cr008 = cr008
            )
        }

        record(input, session, AgentState.L1_RETRIEVING_TOOLS)
        val retrievalStartNs = System.nanoTime()
        val candidateSet = toolCandidateProvider.candidates(normalized, context, ragSnapshot, decision.capabilitySnapshot)
        // CR-008: 候选受控——即使检索器返回了包外 Tool，Prompt 与 LLM 也只能看到
        // 选中 Capability Pack 收敛后的候选（REQ-069 / REQ-070：先过滤再检索、候选受控）。
        val packAllowed = decision.capabilitySnapshot?.filteredToolIds
        val packScopedSet = if (packAllowed != null) {
            candidateSet.copy(candidates = candidateSet.candidates.filter { it.toolId in packAllowed })
        } else {
            candidateSet
        }
        timings.addRouteAndPolicy(msSince(retrievalStartNs))
        val candidateToolIds = packScopedSet.candidates.map { it.toolId }.toSet()
        // CR-017: L1 检索事实（快照导出 retrievalInvoked / retrievedCandidateCount）。
        candidateTrace.retrievalInvoked = candidateSet.source == CandidateSetSource.RETRIEVED
        candidateTrace.retrievedCandidateCount = packScopedSet.candidates.size
        // CR-018: L1 候选 Top-K（RAG Top-K 诊断导出）。
        candidateTrace.retrievedCandidateIds = packScopedSet.candidates.map { it.toolId }
        candidateTrace.retrievedCandidateScores = packScopedSet.candidates.map { it.score }
        val ragInfo = RagExecutionInfo(
            configuredEnabled = ragSnapshot.enabled,
            runtimeStatus = ragRuntimeManager?.status() ?: RagRuntimeStatus.DISABLED,
            retrievalExecuted = candidateSet.source == net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSetSource.RETRIEVED,
            retrievalType = candidateSet.retrieverType,
            fallbackReason = candidateSet.fallbackReason,
            // CR-011 可观测性：检索输入 / 输出 / 候选规模（调试面板展示）。
            queryText = normalized.normalized,
            modelId = ragRuntimeManager?.embeddingModelId(),
            topK = ragSnapshot.toolTopK,
            topScores = packScopedSet.candidates.map { it.score },
            selectedCanonicalIds = packScopedSet.candidates.map { it.toolId },
            retrievedTitles = packScopedSet.candidates.map { it.definition.name },
            eligibleCandidateCount = candidateSet.candidates.size,
            filteredCandidateCount = packScopedSet.candidates.size,
            searchLatencyMs = msSince(retrievalStartNs)
        )

        // CR-017（REQ-170 / IVAI-CANDIDATE-001）：候选为空或无法形成受控 CandidateSet
        // → 明确终态 + reasonCode，不调用模型，不留空 L1 快照。
        if (packScopedSet.candidates.isEmpty()) {
            tracker.transition(IntentTier.L1_LOCAL_TOOL_REASONING, RouteReasonCode.L1_CANDIDATE_EMPTY)
            tracker.finalReasonCode = RouteReasonCode.L1_CANDIDATE_EMPTY
            return turnFailed(
                input, session, timings, Cr016ErrorCodes.CANDIDATE_SET_EMPTY,
                "暂时无法为本次请求形成受控候选集，请换一种说法", false, tracker, ragInfo,
                "L1 候选为空（retrievalInvoked=${candidateTrace.retrievalInvoked}）", cr008 = cr008
            )
        }

        // CR-017（REQ-173）：位置 Alias 命中但当前车型座舱拓扑不适用 → 明确错误，禁止执行。
        val positionEvidence = positionResolver.resolve(normalized.normalized, vehicleModel)
        if (positionEvidence.hasTopologyViolation) {
            tracker.transition(IntentTier.L1_LOCAL_TOOL_REASONING, RouteReasonCode.L1_CANDIDATE_EMPTY)
            tracker.finalReasonCode = Cr017ErrorCodes.ALIAS_TOPOLOGY
            return turnFailed(
                input, session, timings, Cr017ErrorCodes.ALIAS_TOPOLOGY,
                "该位置在当前车型上不可用", false, tracker, ragInfo,
                "位置不适用于当前车型座舱拓扑：${positionEvidence.topologyViolations.joinToString(",")}",
                cr008 = cr008
            )
        }

        val contextStartNs = System.nanoTime()
        val composedMessages = promptBuilder.buildWithCandidates(
            session, input, context.vehicleState, packScopedSet.candidates,
            positionEvidence = positionEvidence
        )
        timings.contextAndPromptMs = msSince(contextStartNs)

        val modelStartNs = System.nanoTime()
        val modelResponse: ModelResponse = try {
            callModel(input, session, composedMessages, timings)
        } catch (e: ModelClientException) {
            timings.modelCallTotalMs = msSince(modelStartNs)
            val code = mapModelError(e.kind)
            tracker.candidateSource = CandidateSource.L1_LOCAL_LLM
            // IVAI-MODEL-002（响应解析失败）≠ 服务不可用：文案需区分，避免误导。
            val message = if (e.kind == ModelErrorKind.RESPONSE_PARSE_ERROR) {
                "模型返回格式异常，请稍后再试"
            } else {
                "模型服务暂不可用，请稍后重试"
            }
            return turnFailed(
                input, session, timings, code.code, message, true,
                tracker, ragInfo, modelFailureDetail(e, "模型调用失败"), cr008 = cr008
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            timings.modelCallTotalMs = msSince(modelStartNs)
            tracker.candidateSource = CandidateSource.L1_LOCAL_LLM
            return turnFailed(
                input, session, timings, ErrorCode.MODEL_UNAVAILABLE.code, "模型服务暂不可用，请稍后重试", true,
                tracker, ragInfo, "模型调用异常：${e.message}", cr008 = cr008
            )
        }
        timings.modelCallTotalMs = msSince(modelStartNs)
        timings.network = modelResponse.network
        timings.providerCompute = modelResponse.providerCompute
        timings.timeToFirstTokenMs = modelResponse.timeToFirstTokenMs
        record(input, session, AgentState.MODEL_RESPONDED, latencyMs = modelResponse.latencyMs)

        // --- parse structured output (CR-016: constrained parse → safe repair) ---
        val parseStartNs = System.nanoTime()
        val parsed = ConstrainedModelResponseParser.parse(
            modelResponse.content,
            expectedTopLevelFields = setOf("route")
        )
        when (parsed) {
            is ParsedModelResponse.Failed -> {
                timings.parseAndSchemaMs = msSince(parseStartNs)
                if (parsed.structureInvalid) {
                    // 合法 JSON 但结构不满足 AgentOutput → IVAI-SCHEMA-001（OUTPUT_SCHEMA）。
                    return turnFailed(
                        input, session, timings, ErrorCode.OUTPUT_SCHEMA.code,
                        "暂时无法安全理解该请求，请换一种说法", false, tracker, ragInfo,
                        "结构化输出不符合 Schema：${parsed.message}\n—— 原始返回 ——\n${modelResponse.content}", cr008 = cr008
                    )
                }
                // CR-016：无法安全修复（IVAI-MODEL-REPAIR-001）；汇总码仍为 IVAI-MODEL-002。
                return turnFailed(
                    input, session, timings, ErrorCode.MODEL_RESPONSE_PARSE.code,
                    "暂时无法理解你的请求，请稍后再试", true, tracker, ragInfo,
                    "模型返回内容无法安全解析/修复：${parsed.message}\n—— 原始返回 ——\n${modelResponse.content}", cr008 = cr008
                )
            }
            is ParsedModelResponse.Success -> {
                record(input, session, AgentState.PARSED)
                val output: AgentOutput = try {
                    json.decodeFromJsonElement(AgentOutput.serializer(), parsed.element)
                } catch (e: Exception) {
                    timings.parseAndSchemaMs = msSince(parseStartNs)
                    return turnFailed(
                        input, session, timings, ErrorCode.OUTPUT_SCHEMA.code,
                        "暂时无法安全理解该请求，请换一种说法", false, tracker, ragInfo,
                        "结构化输出不符合 Schema：${e.message}\n—— 原始返回 ——\n${parsed.element}", cr008 = cr008
                    )
                }
                // CR-016：候选集约束 + 旧 ID canonicalization + 参数 canonicalization。
                // 边界通过时返回 canonical 化后的 output（旧 ID → canonical Tool ID）。
                val boundary = applyCandidateBoundary(
                    input, session, output, candidateToolIds, tracker, ragInfo, timings, cr008
                )
                val effectiveOutput: AgentOutput = when (boundary) {
                    is CandidateBoundaryResult.Rewritten -> boundary.output
                    CandidateBoundaryResult.Passthrough -> output
                    is CandidateBoundaryResult.Blocked -> {
                        timings.parseAndSchemaMs = msSince(parseStartNs)
                        tracker.finalReasonCode = boundary.errorCode
                        return turnFailed(
                            input, session, timings, boundary.errorCode,
                            "该位置表达无法安全执行，请明确位置或换一种说法", false, tracker, ragInfo,
                            boundary.reason, cr008 = cr008
                        )
                    }
                }
                timings.parseAndSchemaMs = msSince(parseStartNs)

                // --- route decision (model-proposed route) ---
                val routeStartNs = System.nanoTime()
                val routeDecision = router.resolve(effectiveOutput)
                if (routeDecision is net.hwyz.iov.vehicle.ivi.ivai.agent.router.RouteDecision.Unsafe) {
                    timings.addRouteAndPolicy(msSince(routeStartNs))
                    return turnFailed(
                        input, session, timings, ErrorCode.ROUTE_UNSAFE.code,
                        "暂时无法安全处理该请求", false, tracker, ragInfo, routeDecision.message,
                        state = AgentState.REJECTED, cr008 = cr008
                    )
                }
                val route = (routeDecision as net.hwyz.iov.vehicle.ivi.ivai.agent.router.RouteDecision.Safe).route
                session.setRoute(route)
                tracker.candidateSource = CandidateSource.L1_LOCAL_LLM

                return when (route) {
                    AgentRoute.LOCAL_DIALOGUE -> handleDialogue(
                        input, session, effectiveOutput, timings, modelResponse, routeStartNs,
                        tracker, ragInfo, candidateToolIds, cr008
                    )
                    AgentRoute.CLOUD_AI -> {
                        timings.addRouteAndPolicy(msSince(routeStartNs))
                        tracker.transition(IntentTier.L3_CLOUD_AI, RouteReasonCode.L3_OPEN_DOMAIN)
                        tracker.finalReasonCode = RouteReasonCode.L3_OPEN_DOMAIN
                        // CLOUD_AI 不是缺参追问场景，模型编造的 missingArguments 不得展示为「缺参」。
                        val sanitized = effectiveOutput.copy(missingArguments = emptyList())
                        val text = "该请求需要云端 AI 处理（预留功能，暂不执行）。"

                        finish(
                            input, session, AgentState.CLOUD_REQUIRED, route, sanitized, emptyList(), null, null,
                            text, modelResponse.content, false, timings,
                            validJson = true, schemaPassed = true, toolExecuted = false,
                            executionPath = tracker.build(), ragInfo = ragInfo, cr008 = cr008
                        ).also { emit(AgentEvent.Reply(session.sessionId, input.turnId, input.requestId, text, tracker.build())) }
                    }
                    AgentRoute.REJECT -> {
                        timings.addRouteAndPolicy(msSince(routeStartNs))
                        tracker.transition(IntentTier.REJECT, RouteReasonCode.REJECT_UNSUPPORTED)
                        tracker.finalReasonCode = RouteReasonCode.REJECT_UNSUPPORTED
                        // 模型可给出拒绝理由（如 TOOL_NOT_AVAILABLE）；但 REJECT 不是缺参追问场景，
                        // 模型编造的 missingArguments（如 blower_mode）不得展示为「缺参」。
                        val sanitized = effectiveOutput.copy(missingArguments = emptyList())
                        val text = "已拒绝该请求。"

                        finish(
                            input, session, AgentState.REJECTED, route, sanitized, emptyList(), null, null,
                            text, modelResponse.content, false, timings,
                            validJson = true, schemaPassed = true, toolExecuted = false,
                            executionPath = tracker.build(), ragInfo = ragInfo, cr008 = cr008
                        ).also { emit(AgentEvent.Reply(session.sessionId, input.turnId, input.requestId, text, tracker.build())) }
                    }
                    AgentRoute.LOCAL_TOOL -> handleLocalTool(
                        input, session, effectiveOutput, timings, modelResponse, routeStartNs, tracker, ragInfo, candidateToolIds, cr008
                    )
                }
            }
        }
    }

    /** L2 path: Knowledge retrieval → local LLM natural-language answer with sources. */
    private suspend fun handleL2(
        input: AgentInput,
        session: Session,
        decision: TieredRouteDecision,
        context: AgentContext,
        timings: TurnTimings,
        ragSnapshot: RagExecutionSnapshot
    ): AgentResult {
        record(input, session, AgentState.L2_RETRIEVING_KNOWLEDGE)
        val tracker = ExecutionPathTracker(IntentTier.L2_LOCAL_KNOWLEDGE)
        tracker.finalReasonCode = decision.reasonCode
        val cr008 = cr008Of(decision)
        val query = (decision.retrievalQuery as? RetrievalQuery.Knowledge)?.query
            ?: KnowledgeRetrievalQuery(text = input.text)

        val knowledgeUnavailable = !ragSnapshot.knowledgeRagAvailable || knowledgeRetriever == null
        val ragInfo = RagExecutionInfo(
            configuredEnabled = ragSnapshot.enabled,
            runtimeStatus = ragRuntimeManager?.status() ?: RagRuntimeStatus.DISABLED,
            retrievalExecuted = !knowledgeUnavailable,
            retrievalType = ragSnapshot.knowledgeRetrieverType,
            // CR-011 可观测性：检索输入 / 模型 / Top-K（调试面板展示）。
            queryText = query.text,
            modelId = ragRuntimeManager?.embeddingModelId(),
            topK = ragSnapshot.knowledgeTopK
        )

        if (knowledgeUnavailable) {
            // Knowledge RAG 不可用：不得让 LLM 凭记忆冒充说明书回答。
            tracker.transition(IntentTier.L3_CLOUD_AI, RouteReasonCode.L2_NO_KNOWLEDGE)
            tracker.finalReasonCode = RouteReasonCode.L2_NO_KNOWLEDGE
            val text = "本地知识库未启用，无法在本机回答该问题。该请求需云端处理（预留功能，暂不执行）。"
            
            return finish(
                input, session, AgentState.CLOUD_REQUIRED, AgentRoute.CLOUD_AI, null, emptyList(), null, null,
                text, null, false, timings,
                validJson = false, schemaPassed = false, toolExecuted = false,
                executionPath = tracker.build(), ragInfo = ragInfo.copy(fallbackReason = RouteReasonCode.L2_NO_KNOWLEDGE),
                cr008 = cr008
            ).also { emit(AgentEvent.Reply(session.sessionId, input.turnId, input.requestId, text, tracker.build())) }
        }

        val retrievalStartNs = System.nanoTime()
        val chunks = knowledgeRetriever!!.retrieve(query, ragSnapshot.knowledgeTopK)
        val reranked = (knowledgeReranker?.rerank(chunks, ragSnapshot.knowledgeTopK) ?: chunks)
        timings.addRouteAndPolicy(msSince(retrievalStartNs))

        if (reranked.isEmpty()) {
            // 检索结果为空或低于阈值：不生成确定性答案，转云。
            tracker.transition(IntentTier.L3_CLOUD_AI, RouteReasonCode.RAG_RETRIEVAL_EMPTY)
            tracker.finalReasonCode = RouteReasonCode.RAG_RETRIEVAL_EMPTY
            val text = "本地知识库未找到相关说明，暂时无法确认。该请求已转云端处理（预留功能，暂不执行）。"
            
            return finish(
                input, session, AgentState.CLOUD_REQUIRED, AgentRoute.CLOUD_AI, null, emptyList(), null, null,
                text, null, false, timings,
                validJson = false, schemaPassed = false, toolExecuted = false,
                executionPath = tracker.build(), ragInfo = ragInfo.copy(fallbackReason = RouteReasonCode.RAG_RETRIEVAL_EMPTY),
                cr008 = cr008
            ).also { emit(AgentEvent.Reply(session.sessionId, input.turnId, input.requestId, text, tracker.build())) }
        }

        // CR-011 可观测性：检索输出（召回片段标题 / 耗时）。
        val ragResult = ragInfo.copy(
            retrievedTitles = reranked.map { it.chunk.title },
            selectedCanonicalIds = reranked.map { it.chunk.chunkId },
            topScores = reranked.map { it.score },
            searchLatencyMs = msSince(retrievalStartNs)
        )

        val contextStartNs = System.nanoTime()
        val composedMessages = promptBuilder.buildKnowledge(session, input, context.vehicleState, reranked.map { it.chunk })
        timings.contextAndPromptMs = msSince(contextStartNs)

        val modelStartNs = System.nanoTime()
        val modelResponse: ModelResponse = try {
            callModel(input, session, composedMessages, timings)
        } catch (e: ModelClientException) {
            timings.modelCallTotalMs = msSince(modelStartNs)
            val code = mapModelError(e.kind)
            return turnFailed(input, session, timings, code.code, "模型服务暂不可用，请稍后重试", true, tracker, ragResult, modelFailureDetail(e, "模型调用失败"), cr008 = cr008)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            timings.modelCallTotalMs = msSince(modelStartNs)
            return turnFailed(input, session, timings, ErrorCode.MODEL_UNAVAILABLE.code, "模型服务暂不可用，请稍后重试", true, tracker, ragResult, "模型调用异常：${e.message}", cr008 = cr008)
        }
        timings.modelCallTotalMs = msSince(modelStartNs)
        timings.network = modelResponse.network
        timings.providerCompute = modelResponse.providerCompute
        timings.timeToFirstTokenMs = modelResponse.timeToFirstTokenMs
        record(input, session, AgentState.MODEL_RESPONDED, latencyMs = modelResponse.latencyMs)

        val rawAnswer = modelResponse.content?.trim().orEmpty()
        val citations = KnowledgeCitationMapper.citations(reranked.map { it.chunk })
        val text = if (rawAnswer.isBlank()) {
            "本地知识库未找到相关说明。"
        } else {
            rawAnswer
        }
        tracker.finalReasonCode = RouteReasonCode.L2_KNOWLEDGE_DOMAIN
        
        return finish(
            input, session, AgentState.REPLY_READY, AgentRoute.LOCAL_DIALOGUE, null, emptyList(), null, null,
            text, modelResponse.content, false, timings,
            validJson = false, schemaPassed = false, toolExecuted = false,
            executionPath = tracker.build(), ragInfo = ragResult, cr008 = cr008
        ).also { emit(AgentEvent.Reply(session.sessionId, input.turnId, input.requestId, text, tracker.build())) }
    }

    private suspend fun handleL3(
        input: AgentInput,
        session: Session,
        decision: TieredRouteDecision,
        timings: TurnTimings
    ): AgentResult {
        record(input, session, AgentState.CLOUD_REQUIRED)
        val tracker = ExecutionPathTracker(IntentTier.L3_CLOUD_AI)
        tracker.finalReasonCode = decision.reasonCode
        val text = "该请求需要云端 AI 处理（预留功能，暂不执行）。"
        
        return finish(
            input, session, AgentState.CLOUD_REQUIRED, AgentRoute.CLOUD_AI, null, emptyList(), null, null,
            text, null, false, timings,
            validJson = false, schemaPassed = false, toolExecuted = false,
            executionPath = tracker.build(), cr008 = cr008Of(decision)
        ).also { emit(AgentEvent.Reply(session.sessionId, input.turnId, input.requestId, text, tracker.build())) }
    }

    private suspend fun handleReject(
        input: AgentInput,
        session: Session,
        decision: TieredRouteDecision,
        timings: TurnTimings
    ): AgentResult {
        record(input, session, AgentState.REJECTED)
        val tracker = ExecutionPathTracker(IntentTier.REJECT)
        tracker.finalReasonCode = decision.reasonCode
        val cr008 = cr008Of(decision)
        val text: String
        val errorCode: String?
        when (decision.reasonCode) {
            RouteReasonCode.REJECT_SAFETY -> {
                text = "该操作涉及驾驶安全，已拒绝。"
                errorCode = null
            }
            // CR-008: 已识别业务领域但无可用 Capability Pack → IVAI-CAP-001。
            RouteReasonCode.DOMAIN_NO_AVAILABLE_PACK -> {
                record(input, session, AgentState.NO_AVAILABLE_CAPABILITY, route = AgentRoute.REJECT.name)
                text = "该功能暂未开放（所属能力包未启用）。"
                errorCode = ErrorCode.NO_AVAILABLE_CAPABILITY.code
            }
            else -> {
                text = "已拒绝该请求。"
                errorCode = null
            }
        }
        
        return finish(
            input, session, AgentState.REJECTED, AgentRoute.REJECT, null, emptyList(), null,
            errorCode, text, null, false, timings,
            validJson = false, schemaPassed = false, toolExecuted = false,
            executionPath = tracker.build(), cr008 = cr008
        ).also { emit(AgentEvent.Reply(session.sessionId, input.turnId, input.requestId, text, tracker.build())) }
    }

    /**
     * CR-008: Workflow 直达路径（已注册 Workflow 被唯一匹配）。
     * 经 WorkflowValidator + WorkflowRuntime 执行；每个步骤仍走 Schema / Policy /
     * 确认 / 幂等 / 执行安全链。WorkflowRuntime 未注入时不可执行（IVAI-WORKFLOW-001）。
     */
    private suspend fun handleWorkflow(
        input: AgentInput,
        session: Session,
        decision: TieredRouteDecision,
        timings: TurnTimings
    ): AgentResult {
        val workflow = decision.workflow ?: return handleReject(input, session, decision, timings)
        record(input, session, AgentState.WORKFLOW_SELECTED)
        // CR-012: Workflow 目标冻结（首期无业务参数）。
        candidateTrace.selectedTarget = ActualTarget(TargetType.WORKFLOW, workflow.workflowId)
        candidateTrace.normalizedArguments = buildJsonObject { }
        val runtime = workflowRuntime
        if (runtime == null) {
            return turnFailed(
                input, session, timings, ErrorCode.WORKFLOW_INVALID.code,
                "Workflow 运行时未配置，无法执行「${workflow.name}」", false, null, null, null,
                state = AgentState.FAILED, cr008 = cr008Of(decision).copy(
                    workflowId = workflow.workflowId,
                    workflowName = workflowName(workflow.workflowId)
                )
            )
        }
        record(input, session, AgentState.WORKFLOW_VALIDATED)
        val execContext = ExecutionContext(input.requestId, session.sessionId, input.source)
        val execStartNs = System.nanoTime()
        val result = runtime.execute(
            workflow = workflow,
            arguments = emptyMap(),
            context = execContext,
            approval = { true }
        )
        timings.toolExecutionMs = msSince(execStartNs)

        val succeeded = result.state == WorkflowExecutionState.SUCCEEDED
        val state = if (succeeded) AgentState.SUCCEEDED else AgentState.FAILED
        val errorCode = if (succeeded) null else result.errorCode ?: ErrorCode.WORKFLOW_FAILED.code
        val text = if (succeeded) "「${workflow.name}」执行完成。" else result.message
        val execPath = AgentExecutionPath(
            initialTier = IntentTier.WORKFLOW_EXECUTION,
            finalTier = IntentTier.WORKFLOW_EXECUTION,
            transitions = emptyList(),
            finalReasonCode = decision.reasonCode,
            candidateSource = null
        )
        val cr008 = cr008Of(decision).copy(
            workflowId = workflow.workflowId,
            workflowName = workflowName(workflow.workflowId),
            workflowState = result.state.name,
            workflowStepCount = result.stepResults.size,
            workflowStepResults = result.stepResults.map { "${it.stepId}:${it.state.name}" },
            compensationResult = result.compensationResult?.let { "${it.stepId}:${it.state.name}" },
            workflowErrorCode = errorCode
        )
        // 先写快照（finish）再发终态事件，避免测试 Runner 读到「有事件无快照」的竞态。
        return finish(
            input, session, state, AgentRoute.LOCAL_TOOL, null, emptyList(), null,
            errorCode, text, null, false, timings,
            validJson = false, schemaPassed = true, toolExecuted = succeeded,
            executionPath = execPath, cr008 = cr008
        ).also {
            emit(
                AgentEvent.ToolExecutionFinished(
                    session.sessionId, input.turnId, input.requestId, workflow.workflowId,
                    status = if (succeeded) ExecutionStatus.SUCCEEDED else ExecutionStatus.FAILED,
                    message = result.message,
                    errorCode = errorCode,
                    retryable = !succeeded,
                    executionPath = execPath
                )
            )
        }
    }

    /** L0 missing-argument fast path: ask for the missing slots without a model call. */
    private suspend fun handleMissingArgumentsDialogue(
        input: AgentInput,
        session: Session,
        toolId: String,
        missing: List<String>,
        tracker: ExecutionPathTracker,
        timings: TurnTimings,
        cr008: Cr008DebugInfo? = null
    ): AgentResult {
        val intent = Intent(toolId = toolId, arguments = emptyMap())
        session.storePendingTask(
            intent, needConfirmation = false, missingArguments = missing,
            executionPath = tracker.build()
        )
        record(input, session, AgentState.NEED_DIALOGUE)
        record(input, session, AgentState.WAITING_USER)
        val text = "请问需要补充：${missing.joinToString("、")}。"
        
        return finish(
            input, session, AgentState.WAITING_USER, AgentRoute.LOCAL_DIALOGUE, null, emptyList(), null, null,
            text, null, false, timings,
            validJson = true, schemaPassed = true, toolExecuted = false,
            executionPath = tracker.build(), cr008 = cr008
        ).also { emit(AgentEvent.Reply(session.sessionId, input.turnId, input.requestId, text, tracker.build())) }
    }

    // ------------------------------------------------------------------ unified execution chain

    /**
     * Unified safe execution chain for ANY tool candidate (L0 / L1 / L3):
     * whitelist → schema → availability → policy → confirmation → idempotency →
     * executor → adapter. L0 only skips RAG/LLM — never an execution safety step.
     */
    private suspend fun executeCandidate(
        input: AgentInput,
        session: Session,
        candidate: ToolCallCandidate,
        tracker: ExecutionPathTracker,
        timings: TurnTimings,
        rawModelContent: String?,
        cr008: Cr008DebugInfo? = null
    ): AgentResult {
        val toolId = candidate.toolId
        val tool = registry.get(toolId)
        if (tool == null) {
            return turnFailed(
                input, session, timings, ErrorCode.UNKNOWN_TOOL.code,
                "无法执行：未知工具 $toolId", false, tracker, null, null, cr008 = cr008
            )
        }
        val values = validator.applyDefaultsAndNormalize(tool, candidate.arguments)
        val issues = validator.validateArguments(tool, values)
        record(input, session, AgentState.VALIDATED)
        if (issues.isNotEmpty()) {
            val first = issues.first()
            emitLifecycle(ToolLifecyclePhase.FAILED, input.requestId, toolId, first.message)
            return turnFailed(
                input, session, timings, ErrorCode.INVALID_ARGUMENT.code,
                "暂时无法安全理解该请求，请换一种说法", false, tracker, null, first.message, cr008 = cr008
            )
        }
        val intent = Intent(toolId = toolId, arguments = valuesToJsonArgs(values))
        val output = AgentOutput(
            route = AgentRoute.LOCAL_TOOL.name,
            intents = listOf(intent),
            modelConfidence = candidate.confidence ?: 1.0,
            riskLevel = tool.policy.riskLevel.name.lowercase(),
            needConfirmation = false,
            reasonCode = candidate.evidenceIds.firstOrNull()
        )
        val policyStartNs = System.nanoTime()
        val outcome = agentPolicy.evaluate(
            listOf(intent), output, vehicleState(), confirmationGranted = false
        )
        timings.addRouteAndPolicy(msSince(policyStartNs))
        when (outcome) {
            is PolicyOutcome.NeedsConfirmation -> {
                val confirmationId = UUID.randomUUID().toString()
                val name = registry.get(toolId)?.name ?: toolId
                session.storePendingTask(
                    intent, needConfirmation = true, missingArguments = emptyList(),
                    confirmationId = confirmationId, toolName = name,
                    requestId = input.requestId, turnId = input.turnId,
                    executionPath = tracker.build()
                )
                record(input, session, AgentState.NEED_DIALOGUE)
                record(input, session, AgentState.WAITING_USER)
                
                return finish(
                    input, session, AgentState.WAITING_USER, AgentRoute.LOCAL_TOOL, output, emptyList(), null, null,
                    "确认执行「$name」？回复「${config.confirmationKeyword}」以继续。", rawModelContent, false,
                    timings, validJson = true, schemaPassed = true, toolExecuted = false,
                    executionPath = tracker.build(), cr008 = cr008
                ).also { emit(
                    AgentEvent.ConfirmationRequired(
                        session.sessionId, input.turnId, input.requestId,
                        confirmationId = confirmationId, toolId = toolId, toolName = name,
                        text = "确认执行「$name」？", currentTier = tracker.currentTier
                    )
                ) }
            }
            is PolicyOutcome.Denied -> {
                emitLifecycle(ToolLifecyclePhase.FAILED, input.requestId, toolId, outcome.message)
                return turnFailed(
                    input, session, timings, outcome.errorCode.code,
                    "该操作未获授权，无法执行", false, tracker, null, outcome.message, cr008 = cr008
                )
            }
            PolicyOutcome.Authorized -> {
                session.clearPendingTask()
                return executeAuthorized(
                    input, session, listOf(intent), output, timings,
                    executionPath = tracker.build(), rawModelContent = rawModelContent, cr008 = cr008
                )
            }
        }
    }

    // ------------------------------------------------------------------ model / confirmation / cancellation

    /**
     * Calls the model, streaming when the provider supports it and
     * [AgentConfig.streamingEnabled] is on: forwards each accumulated delta as a
     * [AgentEvent.StreamingDelta] (rendered into the processing bubble) while
     * still returning the fully accumulated response for parse / validate.
     *
     * CR-014: emits [AgentEvent.ModelCallStarted] before the provider call and
     * [AgentEvent.ModelCallCompleted] once the full response is received (or the
     * call failed / was cancelled), enabling LLM timing in the test collector.
     */
    /**
     * CR-016：通过 [ModelRequestLifecycle] 调用模型，实现分阶段超时（排队/首字/
     * 空闲块/总生成）与取消清理传播。超时以 [ModelClientException] 抛出，由调用方
     * 映射为 IVAI-MODEL-001 汇总码；细分错误码记录在 [ModelCallReport]。
     */
    private suspend fun callModel(
        input: AgentInput,
        session: Session,
        composedMessages: List<ChatMessage>,
        timings: TurnTimings
    ): ModelResponse {
        val request = ModelRequest(
            requestId = input.requestId,
            model = config.model,
            messages = composedMessages,
            timeoutMs = config.requestTimeoutMs
        )
        val providerType = modelProvider::class.simpleName
        emit(AgentEvent.ModelCallStarted(session.sessionId, input.turnId, input.requestId, config.model, providerType))
        // CR-016: 记录模型请求事件（不变量校验 llmInvoked / modelRequestCount）。
        executionState.recordModelRequest()
        llmInvokedThisTurn = true
        val streaming = modelProvider as? StreamingModelProvider
        val sb = StringBuilder()
        return try {
            val outcome = modelRequestLifecycle.call(
                request = request,
                provider = modelProvider,
                onDelta = if (streaming != null && config.streamingEnabled) {
                    { delta ->
                        sb.append(delta)
                        emit(AgentEvent.StreamingDelta(session.sessionId, input.turnId, input.requestId, sb.toString()))
                    }
                } else {
                    null
                }
            )
            when (outcome) {
                is ModelCallOutcome.Success -> {
                    timings.streamingUsed = streaming != null && config.streamingEnabled
                    timings.timeToFirstTokenMs = outcome.report.firstOutputMs
                    outcome.response
                }
                is ModelCallOutcome.Timeout -> {
                    timings.streamingUsed = streaming != null && config.streamingEnabled
                    throw ModelClientException(
                        kind = ModelErrorKind.TIMEOUT,
                        message = outcome.message,
                        cause = null
                    )
                }
                is ModelCallOutcome.ProviderFailure -> throw outcome.exception
            }
        } finally {
            emit(AgentEvent.ModelCallCompleted(session.sessionId, input.turnId, input.requestId, config.model, providerType))
        }
    }

    /**
     * Approves a pending confirmation (IVI-IVAI-DSN-CR-002). Idempotent: only the
     * pending confirmation whose id matches is executed; a missing, stale or
     * mismatched confirmationId is rejected WITHOUT executing the tool.
     */
    suspend fun confirm(confirmationId: String, session: Session): AgentResult {
        candidateTrace.reset()
        val pending = session.pendingTask
        if (pending == null || !pending.needConfirmation || pending.confirmationId != confirmationId) {
            return rejectConfirmation(session, "确认已失效或已被处理")
        }
        val input = pendingInput(pending, confirmationId)
        session.consumePendingTask()
        val execPath = pending.executionPath?.copy(
            finalTier = IntentTier.L1_LOCAL_TOOL_REASONING,
            candidateSource = pending.executionPath.candidateSource ?: CandidateSource.L1_LOCAL_LLM
        )
        return executeAuthorized(
            input, session, listOf(pending.intent), parsedOutput = null,
            timings = TurnTimings(input.submittedAtNs), executionPath = execPath
        )
    }

    /**
     * Cancels a pending confirmation (IVI-IVAI-DSN-CR-002). No tool is ever executed;
     * stale / mismatched ids are rejected without side effects.
     */
    suspend fun cancel(confirmationId: String, session: Session): AgentResult {
        candidateTrace.reset()
        val pending = session.pendingTask
        if (pending == null || !pending.needConfirmation || pending.confirmationId != confirmationId) {
            return rejectConfirmation(session, "确认已失效或已被处理")
        }
        val input = pendingInput(pending, confirmationId)
        val text = "已取消「${pending.toolName ?: pending.intent.toolId}」。"
        session.consumePendingTask()
        session.appendAssistant(text)
        record(input, session, AgentState.REJECTED, route = AgentRoute.LOCAL_TOOL.name)
        val timings = TurnTimings(input.submittedAtNs)
        timings.endToEndMs = msSince(input.submittedAtNs)
        // CR-012: 取消是终态之一（CANCELLED），仍需投影快照供测试采集。
        // 先写快照再发终态事件，避免测试 Runner 读到「有事件无快照」的竞态。
        evaluationListener?.invoke(
            EvaluationSnapshotProjector.project(
                SnapshotFacts(
                    requestId = input.requestId,
                    sessionId = session.sessionId,
                    executionPath = pending.executionPath,
                    initialDomains = candidateTrace.initialDomains,
                    finalDomain = candidateTrace.finalDomain,
                    selectedCapabilityPackIds = candidateTrace.selectedPackIds,
                    state = AgentState.REJECTED,
                    route = AgentRoute.LOCAL_TOOL,
                    pendingConfirmation = false,
                    cancelled = true
                )
            )
        )
        emit(AgentEvent.TurnCancelled(session.sessionId, input.turnId, input.requestId, text))
        return AgentResult(
            requestId = input.requestId,
            sessionId = session.sessionId,
            state = AgentState.REJECTED,
            route = AgentRoute.LOCAL_TOOL,
            responseText = text,
            telemetry = RequestTelemetry(requestId = input.requestId, route = AgentRoute.LOCAL_TOOL.name),
            executionPath = pending.executionPath
        )
    }

    private fun pendingInput(pending: PendingTask, confirmationId: String): AgentInput {
        val requestId = pending.requestId ?: confirmationId
        return AgentInput(
            requestId = requestId,
            text = config.confirmationKeyword,
            source = "confirmation",
            turnId = pending.turnId ?: requestId
        )
    }

    private fun rejectConfirmation(session: Session, message: String): AgentResult {
        val requestId = UUID.randomUUID().toString()
        return AgentResult(
            requestId = requestId,
            sessionId = session.sessionId,
            state = AgentState.REJECTED,
            responseText = message,
            telemetry = RequestTelemetry(requestId = requestId)
        )
    }

    private fun emit(event: AgentEvent) {
        eventListener?.onAgentEvent(event)
    }

    private fun turnFailed(
        input: AgentInput,
        session: Session,
        timings: TurnTimings,
        errorCode: String,
        message: String,
        retryable: Boolean,
        tracker: ExecutionPathTracker?,
        ragInfo: RagExecutionInfo?,
        errorDetail: String?,
        state: AgentState = AgentState.FAILED,
        cr008: Cr008DebugInfo? = null
    ): AgentResult {
        val path = tracker?.build()
        
        return finish(
            input, session, state, null, null, emptyList(), null,
            errorCode, message, null, false, timings,
            validJson = false, schemaPassed = false, toolExecuted = false,
            errorDetail = errorDetail, executionPath = path, ragInfo = ragInfo, cr008 = cr008
        ).also { emit(AgentEvent.TurnFailed(session.sessionId, input.turnId, input.requestId, message, errorCode, retryable, path)) }
    }

    // ------------------------------------------------------------------ branches (model-driven)

    private suspend fun handleDialogue(
        input: AgentInput,
        session: Session,
        output: AgentOutput,
        timings: TurnTimings,
        modelResponse: ModelResponse,
        routeStartNs: Long,
        tracker: ExecutionPathTracker,
        ragInfo: RagExecutionInfo?,
        candidateToolIds: Set<String>?,
        cr008: Cr008DebugInfo? = null
    ): AgentResult {
        // CR-009 修复：不能盲信模型编造的 missingArguments（如 air_volume_support / toolID）。
        // 1) 工具不存在（模型编造 toolId）→ 拒绝，不追问。
        // 2) 按 Tool 的 parameterSchema 重算真实缺失的必填参数。
        // 3) 真实缺参为空（模型误报缺参，实际参数已足）→ 转 LOCAL_TOOL 执行链。
        val intent = output.intents.firstOrNull()
        val tool = intent?.let { registry.get(it.toolId) }
        if (intent == null || tool == null) {
            val unknown = intent?.toolId ?: "unknown"
            emitLifecycle(ToolLifecyclePhase.FAILED, input.requestId, unknown, "unknown tool")
            
            return finish(
                input, session, AgentState.REJECTED, AgentRoute.LOCAL_DIALOGUE, output, emptyList(), null,
                ErrorCode.UNKNOWN_TOOL.code, "LOCAL_DIALOGUE 引用了未知工具：$unknown", modelResponse.content, false, timings,
                validJson = true, schemaPassed = true, toolExecuted = false,
                executionPath = tracker.build(), ragInfo = ragInfo, cr008 = cr008
            ).also { emit(
                AgentEvent.TurnFailed(
                    session.sessionId, input.turnId, input.requestId,
                    message = "暂时无法安全理解该请求，请换一种说法",
                    errorCode = ErrorCode.UNKNOWN_TOOL.code, retryable = false,
                    executionPath = tracker.build()
                )
            ) }
        }

        // 以 Tool 的真实参数 Schema 为准，重算缺失的必填参数（过滤模型编造字段）。
        val values = validator.applyDefaultsAndNormalize(tool, jsonArgsToValues(intent.arguments))
        val schema = net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.SchemaParser.parse(tool.parameterSchema)
        val realMissing = schema.required.filter { required ->
            val provided = values[required]
            provided == null || (provided as? String)?.isBlank() == true
        }

        // 真实缺参为空：模型误报缺参（声称要追问但参数已足，或把不存在的字段当缺参）。
        // 此时不执行（可能映射到错误工具），也不透传编造字段名，改用通用澄清。
        if (realMissing.isEmpty()) {
            timings.addRouteAndPolicy(msSince(routeStartNs))
            record(input, session, AgentState.NEED_DIALOGUE)
            record(input, session, AgentState.WAITING_USER)
            val text = "需要更多信息才能继续处理该请求。"
            
            return finish(
                input, session, AgentState.WAITING_USER, AgentRoute.LOCAL_DIALOGUE, output, emptyList(), null, null,
                text, modelResponse.content, false, timings,
                validJson = true, schemaPassed = true, toolExecuted = false,
                executionPath = tracker.build(), ragInfo = ragInfo, cr008 = cr008
            ).also { emit(AgentEvent.Reply(session.sessionId, input.turnId, input.requestId, text, tracker.build())) }
        }

        session.storePendingTask(
            intent, needConfirmation = false, missingArguments = realMissing,
            executionPath = tracker.build()
        )
        record(input, session, AgentState.NEED_DIALOGUE)
        record(input, session, AgentState.WAITING_USER)
        val text = "请问需要补充：${realMissing.joinToString("、")}。"
        
        return finish(
            input, session, AgentState.WAITING_USER, AgentRoute.LOCAL_DIALOGUE, output, emptyList(), null, null,
            text, modelResponse.content, false, timings,
            validJson = true, schemaPassed = true, toolExecuted = false,
            executionPath = tracker.build(), ragInfo = ragInfo, cr008 = cr008
        ).also { emit(AgentEvent.Reply(session.sessionId, input.turnId, input.requestId, text, tracker.build())) }
    }

    /**
     * CR-017：候选边界（Candidate Boundary）结果。
     *  - [Rewritten]：边界通过，返回 canonical 化后的 output；
     *  - [Passthrough]：非 LOCAL_TOOL 或无法 canonicalize（沿用原 output，由既有链校验/拒绝）；
     *  - [Blocked]：位置 Alias 歧义/冲突（IVAI-ALIAS-AMBIGUOUS-001 等）——硬阻止，禁止执行。
     */
    private sealed interface CandidateBoundaryResult {
        data class Rewritten(val output: AgentOutput) : CandidateBoundaryResult
        data object Passthrough : CandidateBoundaryResult
        data class Blocked(val errorCode: String, val reason: String) : CandidateBoundaryResult
    }

    /**
     * CR-016：候选边界（Candidate Boundary）——非阻断冻结版。
     *
     * 在模型解析后、进入 Policy/Confirmation/执行链之前执行：
     *  1. Tool ID canonicalize（旧 ID / Function-ID → canonical，仅当 registry 未直接
     *     注册该 ID 时映射；旧 ID registry 直接命中则保留——兼容既有链路）；
     *  2. 参数 canonicalization（统一参数语义 + 范围 + 必填）；
     *  3. 候选边界通过后在 CandidateTrace 冻结 canonical Target/Arguments
     *     （FrozenCandidateSnapshot），后续 Policy/Adapter 失败不清空；
     *  4. 边界未通过 → 不冻结（Target/Arguments 保持空），由既有链路按
     *     IVAI-TOOL-001/002/003 汇总码拒绝，细分码进入 trace（不改变兼容码语义）；
     *  5. CR-017：位置 Alias 歧义/冲突（宽泛表达、rear 替代明确排数、与用户证据冲突）
     *     → [CandidateBoundaryResult.Blocked] 硬阻止执行（IVAI-ALIAS-AMBIGUOUS-001）。
     */
    private suspend fun applyCandidateBoundary(
        input: AgentInput,
        session: Session,
        output: AgentOutput,
        candidateToolIds: Set<String>?,
        tracker: ExecutionPathTracker,
        ragInfo: RagExecutionInfo?,
        timings: TurnTimings,
        cr008: Cr008DebugInfo?
    ): CandidateBoundaryResult {
        // 非 LOCAL_TOOL 路由不进入候选边界（对话/云/拒绝不产生可执行目标）。
        if (output.route != AgentRoute.LOCAL_TOOL.name) return CandidateBoundaryResult.Passthrough
        val intent = output.intents.firstOrNull() ?: return CandidateBoundaryResult.Passthrough
        // 1) Tool ID canonicalization：registry 直接注册则保留；否则旧 ID → canonical。
        val rawId = intent.toolId
        val canonicalId = if (registry.get(rawId) != null) {
            rawId
        } else {
            aliasLexicon.canonicalToolId(rawId)
        }
        val tool = registry.get(canonicalId) ?: return CandidateBoundaryResult.Passthrough // 放行：既有链路报 UNKNOWN_TOOL
        // CR-017: 位置证据（用户原话 → 批准 Alias canonical），用于 zone 补齐与歧义/冲突校验。
        val positionResolution = positionResolver.resolve(input.text, vehicleModel)
        // 2) 参数 canonicalization（统一参数语义 + 范围 + 必填）；用户唯一位置证据且模型遗漏 → 补齐。
        var args = jsonArgsToValues(intent.arguments)
        if (positionResolution.singleZone != null && args["zone"] == null) {
            args = args + ("zone" to positionResolution.singleZone)
        }
        val canonical = effectiveCanonicalizer.canonicalize(
            canonicalId, args, CanonicalizationSource.L1_LOCAL_LLM
        )
        if (canonical is CanonicalizationResult.Failed) {
            // 边界未通过：不冻结；细分码进 trace（既有一致汇总码由校验/执行链给出）。
            emitLifecycle(ToolLifecyclePhase.FAILED, input.requestId, canonicalId, canonical.message)
            return CandidateBoundaryResult.Passthrough
        }
        // CR-017（REQ-174/180）：zone 值必须是批准枚举；宽泛表达（right/中间/后面）或与用户
        // 明确位置证据冲突的映射禁止执行（IVAI-ALIAS-AMBIGUOUS-001）。
        val zoneValue = (canonical as CanonicalizationResult.Success).canonicalArguments["zone"] as? String
        if (zoneValue != null) {
            val blocked = when {
                zoneValue !in PositionAliasLexicon.CANONICAL_ZONES ->
                    "zone=$zoneValue 不是批准枚举"
                positionResolution.ambiguousWords.isNotEmpty() ->
                    "宽泛表达 ${positionResolution.ambiguousWords.joinToString("/")} 不得静默映射为 $zoneValue"
                positionResolution.matchedZones.isNotEmpty() && zoneValue !in positionResolution.matchedZones ->
                    "用户位置证据 ${positionResolution.matchedZones.joinToString(",")} 与模型输出 $zoneValue 冲突"
                else -> null
            }
            if (blocked != null) {
                emitLifecycle(
                    ToolLifecyclePhase.FAILED, input.requestId, canonicalId,
                    "$blocked（${Cr017ErrorCodes.ALIAS_AMBIGUOUS}）"
                )
                return CandidateBoundaryResult.Blocked(Cr017ErrorCodes.ALIAS_AMBIGUOUS, blocked)
            }
        }
        // 3) 冻结候选快照（canonical Target/Arguments 在候选边界通过后固化）。
        val success = canonical as CanonicalizationResult.Success
        candidateTrace.freeze(
            type = net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.TargetType.TOOL,
            targetId = canonicalId,
            canonicalArguments = JsonObject(valuesToJsonArgs(success.canonicalArguments)),
            candidateSetHash = candidateSetHash(candidateToolIds),
            source = CandidateSource.L1_LOCAL_LLM,
            matchedRuleIds = emptyList(),
            argumentSources = success.argumentSources
        )
        // 4) 返回 canonical 化后的 output（旧 ID → canonical Tool ID + canonical 参数）。
        val rewritten = intent.copy(
            toolId = canonicalId,
            arguments = valuesToJsonArgs(success.canonicalArguments)
        )
        return CandidateBoundaryResult.Rewritten(output.copy(intents = listOf(rewritten)))
    }

    private fun candidateSetHash(candidateToolIds: Set<String>?): String =
        candidateToolIds?.sorted()?.joinToString(",")?.hashCode()?.toString(16) ?: ""

    private suspend fun handleLocalTool(
        input: AgentInput,
        session: Session,
        output: AgentOutput,
        timings: TurnTimings,
        modelResponse: ModelResponse,
        routeStartNs: Long,
        tracker: ExecutionPathTracker,
        ragInfo: RagExecutionInfo?,
        candidateToolIds: Set<String>?,
        cr008: Cr008DebugInfo? = null
    ): AgentResult {
        // Restore / merge a pending missing-argument task with the same tool.
        val merged = mergePending(session, output)
        val policyStartNs = System.nanoTime()

        // CR-005: the LLM may only select within the recalled Top-K candidate set.
        if (candidateToolIds != null) {
            for (intent in merged.intents) {
                if (intent.toolId in candidateToolIds) continue
                val inRegistry = registry.get(intent.toolId) != null
                return if (inRegistry) {
                    timings.addRouteAndPolicy(msSince(policyStartNs))
                    emitLifecycle(ToolLifecyclePhase.FAILED, input.requestId, intent.toolId, "tool outside candidate set")
                    
                    finish(
                        input, session, AgentState.REJECTED, AgentRoute.LOCAL_TOOL, merged, emptyList(), null,
                        ErrorCode.CANDIDATE_SOURCE_INVALID.code, "工具候选不在本次召回集合内：${intent.toolId}",
                        modelResponse.content, false, timings,
                        validJson = true, schemaPassed = true, toolExecuted = false,
                        executionPath = tracker.build(), ragInfo = ragInfo, cr008 = cr008
                    ).also { emit(
                        AgentEvent.TurnFailed(
                            session.sessionId, input.turnId, input.requestId,
                            message = "暂时无法安全理解该请求，请换一种说法",
                            errorCode = ErrorCode.CANDIDATE_SOURCE_INVALID.code, retryable = false,
                            executionPath = tracker.build()
                        )
                    ) }
                } else {
                    // Not even in the registry → the existing UNKNOWN_TOOL path below.
                    break
                }
            }
        }

        // Whitelist + parameter schema validation.
        val issues = validateIntents(input, session, merged)
        record(input, session, AgentState.VALIDATED)
        if (issues.isNotEmpty()) {
            timings.addRouteAndPolicy(msSince(policyStartNs))
            val unknownTool = issues.any { it.kind == ValidationIssueKind.UNKNOWN_TOOL }
            val errorCode = if (unknownTool) ErrorCode.UNKNOWN_TOOL else ErrorCode.INVALID_ARGUMENT
            emitLifecycle(ToolLifecyclePhase.FAILED, input.requestId, issues.first().toolId.orEmpty(), issues.first().message)
            
            return finish(
                input, session, AgentState.REJECTED, AgentRoute.LOCAL_TOOL, merged, issues, null,
                errorCode.code, issues.first().message, modelResponse.content, false, timings,
                validJson = true, schemaPassed = true, toolExecuted = false,
                executionPath = tracker.build(), ragInfo = ragInfo, cr008 = cr008
            ).also { emit(
                AgentEvent.TurnFailed(
                    session.sessionId, input.turnId, input.requestId,
                    message = "暂时无法安全理解该请求，请换一种说法",
                    errorCode = errorCode.code, retryable = false,
                    executionPath = tracker.build()
                )
            ) }
        }

        // Policy (risk / precondition / confirmation).
        val outcome = agentPolicy.evaluate(merged.intents, output, vehicleState(), confirmationGranted = false)
        timings.addRouteAndPolicy(msSince(policyStartNs))
        when (outcome) {
            is PolicyOutcome.NeedsConfirmation -> {
                val intent = merged.intents.first()
                val confirmationId = UUID.randomUUID().toString()
                val name = registry.get(intent.toolId)?.name ?: intent.toolId
                session.storePendingTask(
                    intent, needConfirmation = true, missingArguments = emptyList(),
                    confirmationId = confirmationId, toolName = name,
                    requestId = input.requestId, turnId = input.turnId,
                    executionPath = tracker.build()
                )
                record(input, session, AgentState.NEED_DIALOGUE)
                record(input, session, AgentState.WAITING_USER)
                
                return finish(
                    input, session, AgentState.WAITING_USER, AgentRoute.LOCAL_TOOL, merged, emptyList(), null, null,
                    "确认执行「$name」？回复「${config.confirmationKeyword}」以继续。", modelResponse.content, false,
                    timings, validJson = true, schemaPassed = true, toolExecuted = false,
                    executionPath = tracker.build(), ragInfo = ragInfo, cr008 = cr008
                ).also { emit(
                    AgentEvent.ConfirmationRequired(
                        session.sessionId, input.turnId, input.requestId,
                        confirmationId = confirmationId, toolId = intent.toolId, toolName = name,
                        text = "确认执行「$name」？", currentTier = tracker.currentTier
                    )
                ) }
            }
            is PolicyOutcome.Denied -> {
                emitLifecycle(ToolLifecyclePhase.FAILED, input.requestId, merged.intents.first().toolId, outcome.message)
                
                return finish(
                    input, session, AgentState.REJECTED, AgentRoute.LOCAL_TOOL, merged, emptyList(), null,
                    outcome.errorCode.code, outcome.message, modelResponse.content, false, timings,
                    validJson = true, schemaPassed = true, toolExecuted = false,
                    executionPath = tracker.build(), ragInfo = ragInfo, cr008 = cr008
                ).also { emit(
                    AgentEvent.TurnFailed(
                        session.sessionId, input.turnId, input.requestId,
                        message = "该操作未获授权，无法执行",
                        errorCode = outcome.errorCode.code, retryable = false,
                        executionPath = tracker.build()
                    )
                ) }
            }
            PolicyOutcome.Authorized -> {
                session.clearPendingTask()
                return executeAuthorized(
                    input, session, merged.intents, merged, timings,
                    modelLatencyMs = modelResponse.latencyMs, rawModelContent = modelResponse.content,
                    executionPath = tracker.build(), cr008 = cr008
                )
            }
        }
    }

    private suspend fun executeAuthorized(
        input: AgentInput,
        session: Session,
        intents: List<Intent>,
        parsedOutput: AgentOutput?,
        timings: TurnTimings,
        modelLatencyMs: Long = -1,
        rawModelContent: String? = null,
        executionPath: AgentExecutionPath? = null,
        cr008: Cr008DebugInfo? = null
    ): AgentResult {
        val intent = intents.first()
        val toolId = intent.toolId
        val tool = registry.get(toolId)
        if (tool == null) {
            
            return finish(
                input, session, AgentState.REJECTED, AgentRoute.LOCAL_TOOL, parsedOutput, emptyList(), null,
                ErrorCode.UNKNOWN_TOOL.code, "未知工具：$toolId", rawModelContent, false, timings,
                validJson = false, schemaPassed = false, toolExecuted = false,
                executionPath = executionPath, cr008 = cr008
            ).also { emit(
                AgentEvent.TurnFailed(
                    session.sessionId, input.turnId, input.requestId,
                    message = "无法执行：未知工具 $toolId",
                    errorCode = ErrorCode.UNKNOWN_TOOL.code, retryable = false,
                    executionPath = executionPath
                )
            ) }
        }
        val values = validator.applyDefaultsAndNormalize(tool, jsonArgsToValues(intent.arguments))
        val toolName = tool.name ?: toolId

        // CR-012: 目标与参数在最终合法候选阶段冻结（Schema 默认值 / Alias 预置 /
        // 单位规范化后的 canonical 参数）。即使 Mock Adapter 执行失败，已确定的目标
        // 和参数仍保留在快照中；执行失败状态单独记录，不改变匹配事实。
        candidateTrace.selectedTarget = ActualTarget(TargetType.TOOL, toolId)
        candidateTrace.normalizedArguments = JsonObject(valuesToJsonArgs(values))

        // Idempotency check: same (requestId, toolId, args) is never re-executed.
        val key = idempotencyGuard.keyFor(input.requestId, toolId, values)
        val cached = idempotencyGuard.find(key)
        if (cached != null) {
            emitLifecycle(ToolLifecyclePhase.REPORTED, input.requestId, toolId, "replayed")
            
            return finish(
                input, session, AgentState.SUCCEEDED, AgentRoute.LOCAL_TOOL, parsedOutput, emptyList(), cached,
                null, "${cached.message}（重复请求，已返回上次执行结果）", rawModelContent, replayed = true, timings,
                validJson = false, schemaPassed = true, toolExecuted = false,
                executionPath = executionPath, cr008 = cr008
            ).also { emit(
                AgentEvent.ToolExecutionFinished(
                    session.sessionId, input.turnId, input.requestId, toolId,
                    status = cached.status,
                    message = "${cached.message}（重复请求，已返回上次执行结果）",
                    errorCode = cached.errorCode,
                    retryable = cached.status != ExecutionStatus.SUCCEEDED,
                    executionPath = executionPath
                )
            ) }
        }

        emitLifecycle(ToolLifecyclePhase.AUTHORIZED, input.requestId, toolId)
        record(input, session, AgentState.AUTHORIZED)
        record(input, session, AgentState.EXECUTING)
        emit(
            AgentEvent.ToolExecutionStarted(
                session.sessionId, input.turnId, input.requestId, toolId,
                text = "正在执行「$toolName」…"
            )
        )
        val execStartNs = System.nanoTime()
        val exec = toolExecutor.execute(
            toolId = toolId,
            arguments = values,
            context = ExecutionContext(input.requestId, session.sessionId, input.source)
        )
        val toolLatencyMs = msSince(execStartNs)
        timings.toolExecutionMs = toolLatencyMs
        idempotencyGuard.record(key, exec)
        session.clearPendingTask()

        val state = when (exec.status) {
            ExecutionStatus.SUCCEEDED -> AgentState.SUCCEEDED
            ExecutionStatus.FAILED -> AgentState.FAILED
            ExecutionStatus.TIMEOUT -> AgentState.TIMEOUT
        }
        val errorCode = if (exec.status == ExecutionStatus.SUCCEEDED) {
            null
        } else {
            exec.errorCode ?: ErrorCode.EXECUTION_FAILED.code
        }
        
        return finish(
            input, session, state, AgentRoute.LOCAL_TOOL, parsedOutput, emptyList(), exec,
            errorCode, exec.message, rawModelContent, false, timings,
            validJson = false, schemaPassed = true, toolExecuted = true, toolLatencyMs = toolLatencyMs,
            executionPath = executionPath, cr008 = cr008
        ).also { emit(
            AgentEvent.ToolExecutionFinished(
                session.sessionId, input.turnId, input.requestId, toolId,
                status = exec.status,
                message = exec.message,
                errorCode = errorCode,
                retryable = exec.status != ExecutionStatus.SUCCEEDED,
                executionPath = executionPath
            )
        ) }
    }

    // ------------------------------------------------------------------ helpers

    private fun mergePending(session: Session, output: AgentOutput): AgentOutput {
        val pending = session.pendingTask ?: return output
        val intent = output.intents.firstOrNull() ?: return output
        if (pending.needConfirmation) return output
        if (intent.toolId == pending.intent.toolId) {
            val mergedArgs = LinkedHashMap(pending.intent.arguments)
            mergedArgs.putAll(intent.arguments)
            return output.copy(intents = listOf(intent.copy(arguments = mergedArgs)))
        }
        // User changed target: drop the stale pending task.
        session.clearPendingTask()
        return output
    }

    private fun validateIntents(
        input: AgentInput,
        session: Session,
        output: AgentOutput
    ): List<ToolValidationIssue> {
        val issues = mutableListOf<ToolValidationIssue>()
        for (intent in output.intents) {
            emitLifecycle(ToolLifecyclePhase.RETRIEVED, input.requestId, intent.toolId)
            emitLifecycle(ToolLifecyclePhase.SELECTED, input.requestId, intent.toolId)
            validator.validateToolId(intent.toolId)?.let { issues += it }
            val tool = registry.get(intent.toolId) ?: continue
            issues += validator.validateArguments(tool, jsonArgsToValues(intent.arguments))
            if (issues.none { it.toolId == intent.toolId }) {
                emitLifecycle(ToolLifecyclePhase.VALIDATED, input.requestId, intent.toolId)
            }
        }
        return issues
    }

    private fun vehicleState(): VehicleStateSnapshot? = vehicleStateProvider?.snapshot()

    /** CR-008: 领域路由 / 能力包可观测信息。 */
    private fun cr008Of(decision: TieredRouteDecision?): Cr008DebugInfo {
        val domain = decision?.domain ?: return Cr008DebugInfo()
        val snapshot = decision.capabilitySnapshot
        return Cr008DebugInfo(
            domain = domain.topDomain?.code,
            operationType = domain.operationType.name,
            domainReasonCode = domain.reasonCode,
            selectedPacks = snapshot?.packs?.map { it.packId } ?: emptyList(),
            // CR-010 展示：选中 Pack 中文名（与 selectedPacks 一一对应）。
            selectedPackNames = snapshot?.packs?.map { packName(it.packId) ?: it.packId } ?: emptyList(),
            packVersion = snapshot?.packVersion,
            postFilterCandidateCount = snapshot?.filteredToolIds?.size
        )
    }

    /** Pack ID → 中文名（治理目录）。 */
    private fun packName(packId: String): String? =
        GovernanceWorkspace.packs.firstOrNull { it.packId == packId }?.name

    /** Tool ID → 中文名（运行时注册表，兜底治理目录）。 */
    private fun toolName(toolId: String): String? =
        registry.get(toolId)?.name ?: ToolCatalogV1.get(toolId)?.name

    /** Workflow ID → 中文名（治理目录）。 */
    private fun workflowName(workflowId: String): String? =
        WorkflowCatalogV1.get(workflowId)?.name

    private fun mapModelError(kind: ModelErrorKind): ErrorCode = when (kind) {
        ModelErrorKind.RESPONSE_PARSE_ERROR -> ErrorCode.MODEL_RESPONSE_PARSE
        else -> ErrorCode.MODEL_UNAVAILABLE
    }

    private fun record(
        input: AgentInput,
        session: Session,
        state: AgentState,
        route: String? = null,
        latencyMs: Long = -1,
        errorCode: String? = null,
        toolId: String? = null,
        toolStatus: String? = null,
        detail: String? = null
    ) {
        telemetryRecorder?.record(
            TelemetryRecord(
                requestId = input.requestId,
                sessionId = session.sessionId,
                state = state.name,
                route = route,
                latencyMs = latencyMs,
                errorCode = errorCode,
                toolId = toolId,
                toolStatus = toolStatus,
                detail = detail
            )
        )
    }

    private fun emitLifecycle(
        phase: ToolLifecyclePhase,
        requestId: String,
        toolId: String,
        message: String? = null
    ) {
        lifecycleListener?.onToolLifecycle(
            ToolLifecycleEvent(phase, requestId, toolId, message)
        )
    }

    private fun finish(
        input: AgentInput,
        session: Session,
        state: AgentState,
        route: AgentRoute?,
        parsedOutput: AgentOutput?,
        validationIssues: List<ToolValidationIssue>,
        executionResult: ToolExecutionResult?,
        errorCode: String?,
        responseText: String,
        rawModelContent: String?,
        replayed: Boolean,
        timings: TurnTimings,
        validJson: Boolean,
        schemaPassed: Boolean,
        toolExecuted: Boolean,
        toolLatencyMs: Long? = null,
        errorDetail: String? = null,
        executionPath: AgentExecutionPath? = null,
        ragInfo: RagExecutionInfo? = null,
        cr008: Cr008DebugInfo? = null
    ): AgentResult {
        if (responseText.isNotBlank()) {
            session.appendAssistant(responseText)
        }
        session.setExecutionPath(executionPath)
        record(
            input, session, state,
            route = route?.name,
            errorCode = errorCode,
            toolId = executionResult?.toolId,
            toolStatus = executionResult?.status?.name,
            detail = errorDetail
        )

        timings.endToEndMs = msSince(input.submittedAtNs)
        val performance = AgentPerformanceMetrics(
            requestId = input.requestId,
            queueMs = timings.queueMs,
            contextAndPromptMs = timings.contextAndPromptMs,
            modelCallTotalMs = timings.modelCallTotalMs,
            network = timings.network,
            providerCompute = timings.providerCompute,
            timeToFirstTokenMs = timings.timeToFirstTokenMs,
            streamingUsed = timings.streamingUsed,
            parseAndSchemaMs = timings.parseAndSchemaMs,
            routeAndPolicyMs = timings.routeAndPolicyMs,
            toolExecutionMs = timings.toolExecutionMs,
            eventDispatchMs = timings.eventDispatchMs,
            endToEndMs = timings.endToEndMs,
            unattributedMs = PerformanceMetricsValidator.unattributedMs(
                timings.endToEndMs, timings.queueMs, timings.contextAndPromptMs,
                timings.modelCallTotalMs, timings.parseAndSchemaMs, timings.routeAndPolicyMs,
                timings.toolExecutionMs, timings.eventDispatchMs
            )
        )

        // CR-012: 终态投影测试快照（脱敏契约；仅 debug/test 接线消费）。
        emitEvaluationSnapshot(
            input = input,
            session = session,
            state = state,
            route = route,
            executionStatus = executionResult?.status,
            executionPath = executionPath
        )

        val dispatchStartNs = System.nanoTime()
        emitDebugInfo(
            input, session, state, route, parsedOutput, executionResult, errorCode,
            rawModelContent, replayed, performance, toolLatencyMs, executionPath, ragInfo, errorDetail,
            cr008
        )
        timings.eventDispatchMs = msSince(dispatchStartNs)
        val performanceFinal = performance.copy(eventDispatchMs = timings.eventDispatchMs)

        return AgentResult(
            requestId = input.requestId,
            sessionId = session.sessionId,
            state = state,
            route = route,
            parsedOutput = parsedOutput,
            validationIssues = validationIssues,
            executionResult = executionResult,
            errorCode = errorCode,
            responseText = responseText,
            rawModelContent = rawModelContent,
            replayed = replayed,
            performance = performanceFinal,
            telemetry = RequestTelemetry(
                requestId = input.requestId,
                modelLatencyMs = timings.modelCallTotalMs ?: -1,
                totalLatencyMs = timings.endToEndMs,
                route = route?.name,
                toolExecuted = toolExecuted,
                validJson = validJson,
                schemaPassed = schemaPassed
            ),
            executionPath = executionPath
        )
    }

    /** CR-012: 终态投影测试快照（脱敏；不包含 Prompt/思维链/密钥/原始响应）。 */
    private fun emitEvaluationSnapshot(
        input: AgentInput,
        session: Session,
        state: AgentState,
        route: AgentRoute?,
        executionStatus: ExecutionStatus?,
        executionPath: AgentExecutionPath?
    ) {
        // CR-016: 终态封口——记录终止阶段 / 原因 / 终态并执行不变量校验。
        val terminalStatus = EvaluationSnapshotProjector.terminalStatus(
            SnapshotFacts(
                requestId = input.requestId,
                sessionId = session.sessionId,
                executionPath = executionPath,
                state = state,
                route = route,
                executionStatus = executionStatus,
                pendingConfirmation = session.pendingConfirmationId != null,
                cancelled = state == AgentState.REJECTED && route == AgentRoute.REJECT
            )
        )
        val terminalStage = terminalStageOf(state, route, executionStatus)
        executionState.finalTier = executionPath?.finalTier
        executionState.markTerminal(
            terminalStage = terminalStage,
            reasonCode = executionPath?.finalReasonCode,
            terminalStatus = terminalStatus
        )
        // CR-017: 模型分发尝试标记（快照导出 modelDispatchAttempted）。
        candidateTrace.modelDispatchAttempted = executionState.llmInvoked
        val invariantResult = invariantValidator.validate(
            executionState.invariantContext(candidateTrace.selectedTarget)
        )
        // 违规：输出 IVAI-STATE-001，不得生成看似正常的空 L1 结果。
        val failureReason: String?
        val reasonCode: String?
        if (invariantResult is InvariantResult.Violated) {
            failureReason = invariantResult.message
            reasonCode = net.hwyz.iov.vehicle.ivi.ivai.agent.error.Cr016ErrorCodes.STATE_INVARIANT
        } else {
            failureReason = null
            reasonCode = executionPath?.finalReasonCode
        }
        val frozen = candidateTrace.frozenSnapshot
        evaluationListener?.invoke(
            EvaluationSnapshotProjector.project(
                SnapshotFacts(
                    requestId = input.requestId,
                    sessionId = session.sessionId,
                    executionPath = executionPath,
                    initialDomains = candidateTrace.initialDomains,
                    finalDomain = candidateTrace.finalDomain,
                    selectedCapabilityPackIds = candidateTrace.selectedPackIds,
                    selectedTarget = candidateTrace.selectedTarget,
                    normalizedArguments = candidateTrace.normalizedArguments,
                    state = state,
                    route = route,
                    executionStatus = executionStatus,
                    pendingConfirmation = session.pendingConfirmationId != null,
                    reasonCode = reasonCode,
                    governanceVersion = candidateTrace.governanceVersion,
                    candidateVersion = candidateTrace.candidateVersion,
                    terminalStage = terminalStage,
                    failureReason = failureReason,
                    candidateSetHash = frozen?.candidateSetHash,
                    matchedRuleIds = frozen?.matchedRuleIds ?: emptyList(),
                    argumentSources = frozen?.argumentSources?.mapValues { (_, v) -> v.name } ?: emptyMap(),
                    llmInvoked = executionState.llmInvoked,
                    modelRequestCount = executionState.modelRequestCount,
                    // CR-017: 检索与模型分发可观测性（REQ-183）。
                    retrievalInvoked = candidateTrace.retrievalInvoked,
                    retrievedCandidateCount = candidateTrace.retrievedCandidateCount,
                    modelDispatchAttempted = candidateTrace.modelDispatchAttempted,
                    // CR-018: 检索 Top-K 可观测性。
                    retrievedCandidateIds = candidateTrace.retrievedCandidateIds,
                    retrievedCandidateScores = candidateTrace.retrievedCandidateScores
                )
            )
        )
    }

    /** CR-016: 状态/路由 → 终止阶段映射（非成功终态必填 terminalStage）。 */
    private fun terminalStageOf(
        state: AgentState,
        route: AgentRoute?,
        executionStatus: ExecutionStatus?
    ): TerminalStage = when {
        executionStatus == ExecutionStatus.SUCCEEDED || state == AgentState.SUCCEEDED ->
            TerminalStage.SUCCESS
        route == AgentRoute.REJECT -> TerminalStage.ROUTING
        state == AgentState.WAITING_USER || state == AgentState.NEED_DIALOGUE ->
            if (route == AgentRoute.LOCAL_DIALOGUE) TerminalStage.CONFIRMATION
            else TerminalStage.CANDIDATE_BOUNDARY
        state == AgentState.REJECTED -> TerminalStage.POLICY
        state == AgentState.TIMEOUT -> TerminalStage.MODEL_REQUEST
        state == AgentState.FAILED -> TerminalStage.EXECUTION
        else -> TerminalStage.SNAPSHOT
    }

    private fun emitDebugInfo(
        input: AgentInput,
        session: Session,
        state: AgentState,
        route: AgentRoute?,
        parsedOutput: AgentOutput?,
        executionResult: ToolExecutionResult?,
        errorCode: String?,
        rawModelContent: String?,
        replayed: Boolean,
        performance: AgentPerformanceMetrics?,
        toolLatencyMs: Long?,
        executionPath: AgentExecutionPath?,
        ragInfo: RagExecutionInfo?,
        errorDetail: String? = null,
        cr008: Cr008DebugInfo? = null
    ) {
        emit(
            AgentEvent.DebugInfo(
                session.sessionId, input.turnId, input.requestId,
                debug = TurnDebugInfo(
                    turnId = input.turnId,
                    requestId = input.requestId,
                    performance = performance,
                    rawModelContent = rawModelContent,
                    parsed = parsedOutput?.let {
                        ParsedOutputSummary(
                            route = it.route,
                            confidence = it.modelConfidence,
                            riskLevel = it.riskLevel,
                            needConfirmation = it.needConfirmation,
                            missingArguments = it.missingArguments,
                            reasonCode = it.reasonCode,
                            intents = it.intents.map { intent ->
                                ParsedIntentSummary(
                                    toolId = intent.toolId,
                                    functionId = intent.functionId,
                                    toolName = toolName(intent.toolId),
                                    arguments = intent.arguments.mapValues { (_, value) -> value.toString() }
                                )
                            }
                        )
                    },
                    tool = executionResult?.let {
                        ToolDebugInfo(
                            toolId = it.toolId,
                            toolName = it.toolId?.let { id -> toolName(id) },
                            status = it.status.name,
                            message = it.message,
                            errorCode = it.errorCode,
                            latencyMs = toolLatencyMs
                        )
                    },
                    state = state.name,
                    route = route?.name,
                    errorCode = errorCode,
                    errorDetail = errorDetail,
                    replayed = replayed,
                    intentTier = executionPath?.initialTier?.name,
                    finalTier = executionPath?.finalTier?.name,
                    transitions = executionPath?.transitions ?: emptyList(),
                    candidateSource = executionPath?.candidateSource?.name,
                    rag = ragInfo,
                    cr008 = cr008
                )
            )
        )
    }

    /**
     * 组装模型调用失败详情：异常信息 + 完整原始返回数据（便于定位模型返回问题）。
     * 原始返回经 TurnDebugInfo.errorDetail 全量透出到失败详情（CR-010 可观测性补充）。
     */
    private fun modelFailureDetail(e: ModelClientException, prefix: String): String {
        val base = "$prefix：${e.message}"
        val raw = e.rawContent
        return if (raw.isNullOrBlank()) base else "$base\n—— 原始返回 ——\n$raw"
    }

    private fun msSince(startNs: Long): Long = (System.nanoTime() - startNs) / NANOS_PER_MILLI

    private fun msBetween(startNs: Long, endNs: Long): Long = (endNs - startNs) / NANOS_PER_MILLI

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
