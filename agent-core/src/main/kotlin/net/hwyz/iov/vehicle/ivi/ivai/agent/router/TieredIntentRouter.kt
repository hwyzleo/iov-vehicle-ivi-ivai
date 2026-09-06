package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilitySnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainAmbiguity
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouteDecision
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.RetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.SemanticFeature
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowRegistry

/**
 * Tiered intent router (IVI-IVAI-DSN-CR-005 + CR-008 + CR-010). Decides the
 * execution tier for a turn following the design order:
 *
 *  input normalization → safety / domain pre-check → domain route + pack select
 *    (CR-008) → unified runtimeCandidateToolIds（CR-010）→ L0 unique match?
 *      ├─ yes → ToolCallCandidate (no RAG / no LLM)
 *      └─ no → L1 Tool/Intent RAG + local LLM（只能在同一统一候选集或其 Top-K 子集内）
 *              └─ no → L2 Knowledge RAG + local LLM
 *                      └─ no → L3 Cloud AI / REJECT
 *
 * CR-010：L0/L1 是请求解析路径而非 Tool 分类。选中 Pack 内全部运行时可执行
 * Tool 经 RuntimeCapabilityAssembler 形成统一 runtimeCandidateToolIds；L0 匹配
 * 与 L1 检索都只在该集合或其 Top-K 子集内工作，不存在独立 L0 白名单。
 * 同一 Tool 可由明确表达走 L0、隐式表达走 L1（IntentTier 描述候选产生方式，
 * 不是 ToolDefinition 的固有属性）。
 */
class TieredIntentRouter(
    private val matcher: FastIntentMatcher,
    private val domainClassifier: DomainClassifier,
    private val safetyKeywords: List<String> = DEFAULT_SAFETY_KEYWORDS,
    private val knowledgeDomainEnabled: Boolean = true,
    // CR-008 (default off → legacy behavior unchanged):
    private val domainRouter: DomainRouter? = null,
    private val capabilitySelector: CapabilityPackSelector? = null,
    private val workflows: List<WorkflowDefinition> = WorkflowRegistry.AVAILABLE,
    /** CR-008/CR-010 统一候选集限定 L0 所需的 Tool 注册表（启用领域路由时由装配方提供）。 */
    private val registry: net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry? = null
) {

    suspend fun route(input: NormalizedInput, context: AgentContext): TieredRouteDecision {
        // Safety / domain pre-check: driving control is always rejected.
        val safetyHit = safetyKeywords.firstOrNull { input.normalized.contains(it) }
        if (safetyHit != null) {
            return TieredRouteDecision(
                tier = IntentTier.REJECT,
                confidence = 1.0,
                reasonCode = RouteReasonCode.REJECT_SAFETY
            )
        }

        val dr = domainRouter
        val cs = capabilitySelector
        if (dr == null || cs == null) {
            return legacyRoute(input, context)
        }

        // CR-008: domain route + pack select BEFORE L0 / L1.
        val domainDecision = dr.route(input, context)
        val snapshot = cs.select(domainDecision, context)

        // CR-010: Workflow 若存在已注册、唯一且参数完整的显式触发，也可由确定性
        // 匹配产生 WorkflowCandidate（每个步骤仍执行完整安全校验）。
        val workflow = matchWorkflow(input, snapshot)
        if (workflow != null) {
            return TieredRouteDecision(
                tier = IntentTier.WORKFLOW_EXECUTION,
                confidence = 0.95,
                reasonCode = RouteReasonCode.WORKFLOW_MATCHED,
                domain = domainDecision,
                capabilitySnapshot = snapshot,
                workflow = workflow,
                observability = observability(snapshot)
            )
        }

        // No available capability pack: hard action requests in a confident domain are
        // gated (IVAI-CAP-001); soft requests (QUERY / UNKNOWN / low-confidence) fall back.
        if (snapshot.packs.isEmpty()) {
            val hardAction = domainDecision.operationType in HARD_ACTION_TYPES
            if (domainDecision.classified && hardAction &&
                domainDecision.ambiguity != DomainAmbiguity.MULTI_DOMAIN
            ) {
                return TieredRouteDecision(
                    tier = IntentTier.REJECT,
                    confidence = domainDecision.confidence,
                    reasonCode = RouteReasonCode.DOMAIN_NO_AVAILABLE_PACK,
                    domain = domainDecision,
                    capabilitySnapshot = snapshot,
                    observability = observability(snapshot)
                )
            }
            // 缺上下文 / 低置信 / 未知 / 软请求：回退旧链路（L1/L2/L3）。
            return legacyRoute(input, context, domainDecision, snapshot)
        }

        // CR-010: L0 scoped to the unified canonical candidate set (no L0 whitelist).
        val scopedMatcher = if (snapshot.runtimeCandidateToolIds.isEmpty() || registry == null) {
            matcher
        } else {
            DefaultFastIntentMatcher(registry, snapshot.runtimeCandidateToolIds)
        }
        return when (val match = scopedMatcher.match(input, context)) {
            is FastIntentMatchResult.Unique -> TieredRouteDecision(
                tier = IntentTier.L0_DETERMINISTIC_TOOL,
                confidence = match.confidence,
                reasonCode = match.reasonCode,
                directCandidate = match.candidate,
                domain = domainDecision,
                capabilitySnapshot = snapshot,
                observability = observability(
                    snapshot,
                    matchedToolIds = match.matchedToolIds,
                    patternId = match.matchedPatternId,
                    canonicalToolId = match.canonicalToolId ?: match.candidate.toolId,
                    errorCode = null
                )
            )
            is FastIntentMatchResult.Ambiguous -> toolDomainDecision(
                reasonCode = RouteReasonCode.L0_RULE_AMBIGUOUS,
                input = input,
                context = context,
                domain = domainDecision,
                snapshot = snapshot,
                observability = observability(
                    snapshot,
                    matchedToolIds = match.matchedToolIds,
                    errorCode = ErrorCodeString.ROUTE_CONFLICT // IVAI-ROUTE-003
                )
            )
            is FastIntentMatchResult.MissingArguments -> toolDomainDecision(
                reasonCode = RouteReasonCode.L0_MISSING_ARGUMENTS,
                input = input,
                context = context,
                domain = domainDecision,
                snapshot = snapshot,
                missingToolId = match.toolId,
                missingArguments = match.missing,
                observability = observability(snapshot, canonicalToolId = match.toolId)
            )
            FastIntentMatchResult.NoMatch -> {
                val l0Reason = when {
                    input.hasNegation -> RouteReasonCode.L0_NEGATED
                    input.hasMultiIntent -> RouteReasonCode.L0_MULTI_INTENT
                    else -> RouteReasonCode.L0_NO_MATCH
                }
                // CR-010：高频明确表达没有确定性匹配元数据 → 记录治理缺口
                // DETERMINISTIC_COVERAGE_MISSING（IVAI-ROUTE-004），仍走 L1；
                // 隐式/强上下文表达属于正常 L1，不记录缺口。
                val coverageGap = isExplicitToolCommand(domainDecision, input) &&
                    l0Reason == RouteReasonCode.L0_NO_MATCH
                toolDomainDecision(
                    reasonCode = l0Reason,
                    input = input,
                    context = context,
                    domain = domainDecision,
                    snapshot = snapshot,
                    observability = observability(
                        snapshot,
                        fallbackReason = if (coverageGap) "DETERMINISTIC_COVERAGE_MISSING" else null,
                        errorCode = if (coverageGap) ErrorCodeString.ROUTE_COVERAGE_MISSING else null // IVAI-ROUTE-004
                    )
                )
            }
        }
    }

    /** CR-010：是否为「可安全直达的显式表达」候选（无隐式语义特征）。 */
    private fun isExplicitToolCommand(domain: DomainRouteDecision, input: NormalizedInput): Boolean {
        if (input.hasNegation || input.hasMultiIntent) return false
        if (domain.semanticFeatures.contains(SemanticFeature.IMPLICIT_EXPRESSION)) return false
        return domain.operationType in EXPLICIT_OPERATION_TYPES
    }

    /** 领域 / 能力包限定后的 L1 工具领域决策。 */
    private fun toolDomainDecision(
        reasonCode: String,
        input: NormalizedInput,
        context: AgentContext,
        domain: DomainRouteDecision?,
        snapshot: CapabilitySnapshot?,
        missingToolId: String? = null,
        missingArguments: List<String> = emptyList(),
        observability: RouteObservability? = null
    ): TieredRouteDecision = TieredRouteDecision(
        tier = IntentTier.L1_LOCAL_TOOL_REASONING,
        confidence = 0.8,
        reasonCode = reasonCode,
        retrievalQuery = RetrievalQuery.Tools(
            ToolRetrievalQuery(
                text = input.normalized,
                vehicleModel = context.vehicleModel,
                softwareVersion = context.softwareVersion,
                domainIds = domain?.candidates?.map { it.domainId } ?: emptyList(),
                capabilityPackIds = snapshot?.packs?.map { it.packId } ?: emptyList()
            )
        ),
        missingToolId = missingToolId,
        missingArguments = missingArguments,
        domain = domain,
        capabilitySnapshot = snapshot,
        observability = observability
    )

    /** 在选中 Pack 的 Workflow 中按触发表达唯一匹配（CR-010 统一 Workflow 候选集）。 */
    private fun matchWorkflow(
        input: NormalizedInput,
        snapshot: CapabilitySnapshot
    ): WorkflowDefinition? {
        if (snapshot.runtimeCandidateWorkflowIds.isEmpty()) return null
        val normalized = input.normalized
        val matched = workflows.filter { candidate ->
            candidate.workflowId in snapshot.runtimeCandidateWorkflowIds &&
                candidate.available &&
                (candidate.triggerExamples.any { normalized.contains(it) } || normalized.contains(candidate.name))
        }
        return matched.singleOrNull()
    }

    /** CR-010 可观测性构造。 */
    private fun observability(
        snapshot: CapabilitySnapshot?,
        matchedToolIds: Set<String> = emptySet(),
        patternId: String? = null,
        canonicalToolId: String? = null,
        fallbackReason: String? = null,
        errorCode: String? = null
    ): RouteObservability = RouteObservability(
        selectedPackIds = snapshot?.selectedPackIds ?: emptySet(),
        runtimeCandidateToolIdsHash = snapshot?.runtimeCandidateToolIdsHash,
        deterministicMatchedToolIds = matchedToolIds,
        matchedPatternId = patternId,
        canonicalToolId = canonicalToolId,
        deterministicFallbackReason = fallbackReason,
        governanceRuntimeMode = snapshot?.governanceRuntimeMode,
        errorCode = errorCode
    )

    /** 旧链路（未注入 CR-008 组件时的完整路由，CR-005 行为不变）。 */
    private suspend fun legacyRoute(
        input: NormalizedInput,
        context: AgentContext,
        domain: DomainRouteDecision? = null,
        snapshot: CapabilitySnapshot? = null
    ): TieredRouteDecision {
        when (val match = matcher.match(input, context)) {
            is FastIntentMatchResult.Unique -> {
                return TieredRouteDecision(
                    tier = IntentTier.L0_DETERMINISTIC_TOOL,
                    confidence = match.confidence,
                    reasonCode = match.reasonCode,
                    directCandidate = match.candidate,
                    domain = domain,
                    capabilitySnapshot = snapshot,
                    observability = observability(
                        snapshot,
                        matchedToolIds = match.matchedToolIds,
                        patternId = match.matchedPatternId,
                        canonicalToolId = match.canonicalToolId
                    )
                )
            }
            is FastIntentMatchResult.Ambiguous -> {
                return toolDomainDecisionLegacy(
                    reasonCode = RouteReasonCode.L0_RULE_AMBIGUOUS,
                    input = input,
                    context = context,
                    domain = domain,
                    snapshot = snapshot,
                    observability = observability(snapshot, matchedToolIds = match.matchedToolIds)
                )
            }
            is FastIntentMatchResult.MissingArguments -> {
                return toolDomainDecisionLegacy(
                    reasonCode = RouteReasonCode.L0_MISSING_ARGUMENTS,
                    input = input,
                    context = context,
                    domain = domain,
                    snapshot = snapshot,
                    missingToolId = match.toolId,
                    missingArguments = match.missing,
                    observability = observability(snapshot, canonicalToolId = match.toolId)
                )
            }
            FastIntentMatchResult.NoMatch -> {
                val l0Reason = when {
                    input.hasNegation -> RouteReasonCode.L0_NEGATED
                    input.hasMultiIntent -> RouteReasonCode.L0_MULTI_INTENT
                    else -> RouteReasonCode.L0_NO_MATCH
                }
                return when (domainClassifier.classify(input)) {
                    IntentDomain.TOOL -> toolDomainDecisionLegacy(
                        reasonCode = l0Reason,
                        input = input,
                        context = context,
                        domain = domain,
                        snapshot = snapshot
                    )
                    IntentDomain.KNOWLEDGE -> knowledgeDecision(input, domain, snapshot)
                    IntentDomain.OPEN -> TieredRouteDecision(
                        tier = IntentTier.L3_CLOUD_AI,
                        confidence = 0.5,
                        reasonCode = RouteReasonCode.L3_OPEN_DOMAIN,
                        domain = domain,
                        capabilitySnapshot = snapshot,
                        observability = observability(snapshot)
                    )
                }
            }
        }
    }

    private fun toolDomainDecisionLegacy(
        reasonCode: String,
        input: NormalizedInput,
        context: AgentContext,
        domain: DomainRouteDecision?,
        snapshot: CapabilitySnapshot?,
        missingToolId: String? = null,
        missingArguments: List<String> = emptyList(),
        observability: RouteObservability? = null
    ): TieredRouteDecision = TieredRouteDecision(
        tier = IntentTier.L1_LOCAL_TOOL_REASONING,
        confidence = 0.8,
        reasonCode = reasonCode,
        retrievalQuery = RetrievalQuery.Tools(
            ToolRetrievalQuery(
                text = input.normalized,
                vehicleModel = context.vehicleModel,
                softwareVersion = context.softwareVersion
            )
        ),
        missingToolId = missingToolId,
        missingArguments = missingArguments,
        domain = domain,
        capabilitySnapshot = snapshot,
        observability = observability
    )

    private fun knowledgeDecision(
        input: NormalizedInput,
        domain: DomainRouteDecision?,
        snapshot: CapabilitySnapshot?
    ): TieredRouteDecision =
        if (!knowledgeDomainEnabled) {
            TieredRouteDecision(
                tier = IntentTier.L3_CLOUD_AI,
                confidence = 0.5,
                reasonCode = RouteReasonCode.L2_NO_KNOWLEDGE,
                domain = domain,
                capabilitySnapshot = snapshot,
                observability = observability(snapshot)
            )
        } else {
            TieredRouteDecision(
                tier = IntentTier.L2_LOCAL_KNOWLEDGE,
                confidence = 0.7,
                reasonCode = RouteReasonCode.L2_KNOWLEDGE_DOMAIN,
                retrievalQuery = RetrievalQuery.Knowledge(
                    KnowledgeRetrievalQuery(text = input.normalized)
                ),
                domain = domain,
                capabilitySnapshot = snapshot,
                observability = observability(snapshot)
            )
        }

    private companion object {
        /** Driving-safety control keywords — always REJECT (design pre-check). */
        val DEFAULT_SAFETY_KEYWORDS = listOf(
            "开车", "驾驶", "刹车", "制动", "转向", "方向盘", "油门", "加速",
            "挂挡", "开走", "变道", "倒车", "漂移", "自动驾驶", "车速"
        )

        /** CR-008: 需要能力包的「硬动作」操作类型（软请求如 QUERY/UNKNOWN 走回退）。 */
        val HARD_ACTION_TYPES = setOf(
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.CONTROL,
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.CONFIGURE,
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.NAVIGATE_UI,
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.PLAYBACK,
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.SEARCH,
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.WORKFLOW
        )

        /** CR-010: 可安全直达的显式表达操作类型（明确对象、动作与参数）。 */
        val EXPLICIT_OPERATION_TYPES = setOf(
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.CONTROL,
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.QUERY,
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.CONFIGURE,
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.NAVIGATE_UI,
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.PLAYBACK
        )
    }
}

/** CR-010 错误码字符串常量（与 ErrorCode 枚举码一致，避免循环依赖 agent-core 内部引用）。 */
object ErrorCodeString {
    const val CAP_CANONICAL_UNCLOSED = "IVAI-CAP-003"
    const val ROUTE_CONFLICT = "IVAI-ROUTE-003"
    const val ROUTE_COVERAGE_MISSING = "IVAI-ROUTE-004"
    const val GOV_DRAFT_PROMOTED = "IVAI-GOV-003"
    const val GOV_STUB_EXEMPTION_INVALID = "IVAI-GOV-004"
    const val ALIAS_CONFLICT = "IVAI-ALIAS-001"
}
