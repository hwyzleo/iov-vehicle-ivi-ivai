package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilityPackSelector
import net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilitySnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainAmbiguity
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouteDecision
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.RetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowRegistry

/**
 * Tiered intent router (IVI-IVAI-DSN-CR-005 + CR-008). Decides the execution
 * tier for a turn following the design order:
 *
 *  input normalization → safety / domain pre-check → domain route + pack select
 *    (CR-008) → L0 unique match?
 *      ├─ yes → ToolCallCandidate (no RAG / no LLM)
 *      └─ no → local tool domain? → L1 Tool/Intent RAG + local LLM
 *              └─ no → local knowledge domain? → L2 Knowledge RAG + local LLM
 *                      └─ no → L3 Cloud AI / REJECT
 *
 * CR-008: when a [DomainRouter] + [CapabilityPackSelector] are injected, the
 * business-domain pre-routing and capability-pack filtering happen BEFORE L0 /
 * L1; L0 is scoped to the selected pack's tools and the L1 retrieval query is
 * restricted to the selected domains / packs. A confident domain with no
 * available pack is rejected as NO_AVAILABLE_CAPABILITY; low-confidence /
 * ambiguous / unknown domains fall back to the legacy classifier path.
 *
 * The router records the hit tier, rule, confidence and reason code; retrieval
 * / prompt / model orchestration happens downstream in the workflow.
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
    /** CR-008 能力包限定 L0 所需的 Tool 注册表（启用领域路由时由装配方提供）。 */
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

        // Workflow fast-path: a registered workflow uniquely matches and is inside
        // the selected packs → WorkflowPlanner selects it (each step still passes
        // the safe execution chain).
        val workflow = matchWorkflow(input, snapshot)
        if (workflow != null) {
            return TieredRouteDecision(
                tier = IntentTier.WORKFLOW_EXECUTION,
                confidence = 0.95,
                reasonCode = RouteReasonCode.WORKFLOW_MATCHED,
                domain = domainDecision,
                capabilitySnapshot = snapshot,
                workflow = workflow
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
                    capabilitySnapshot = snapshot
                )
            }
            // 缺上下文 / 低置信 / 未知 / 软请求：回退旧链路（L1/L2/L3）。
            return legacyRoute(input, context, domainDecision, snapshot)
        }

        // Pack-scoped L0: only tools inside the selected packs may match.
        val scopedMatcher = if (snapshot.filteredToolIds.isEmpty() || registry == null) {
            matcher
        } else {
            DefaultFastIntentMatcher(registry, snapshot.filteredToolIds)
        }
        return when (val match = scopedMatcher.match(input, context)) {
            is FastIntentMatchResult.Unique -> TieredRouteDecision(
                tier = IntentTier.L0_DETERMINISTIC_TOOL,
                confidence = match.confidence,
                reasonCode = match.reasonCode,
                directCandidate = match.candidate,
                domain = domainDecision,
                capabilitySnapshot = snapshot
            )
            is FastIntentMatchResult.Ambiguous -> toolDomainDecision(
                reasonCode = RouteReasonCode.L0_RULE_AMBIGUOUS,
                input = input,
                context = context,
                domain = domainDecision,
                snapshot = snapshot
            )
            is FastIntentMatchResult.MissingArguments -> toolDomainDecision(
                reasonCode = RouteReasonCode.L0_MISSING_ARGUMENTS,
                input = input,
                context = context,
                domain = domainDecision,
                snapshot = snapshot,
                missingToolId = match.toolId,
                missingArguments = match.missing
            )
            FastIntentMatchResult.NoMatch -> {
                val l0Reason = when {
                    input.hasNegation -> RouteReasonCode.L0_NEGATED
                    input.hasMultiIntent -> RouteReasonCode.L0_MULTI_INTENT
                    else -> RouteReasonCode.L0_NO_MATCH
                }
                toolDomainDecision(
                    reasonCode = l0Reason,
                    input = input,
                    context = context,
                    domain = domainDecision,
                    snapshot = snapshot
                )
            }
        }
    }

    /** 领域 / 能力包限定后的 L1 工具领域决策。 */
    private fun toolDomainDecision(
        reasonCode: String,
        input: NormalizedInput,
        context: AgentContext,
        domain: DomainRouteDecision?,
        snapshot: CapabilitySnapshot?,
        missingToolId: String? = null,
        missingArguments: List<String> = emptyList()
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
        capabilitySnapshot = snapshot
    )

    /** 在选中 Pack 的 Workflow 中按触发表达唯一匹配。 */
    private fun matchWorkflow(
        input: NormalizedInput,
        snapshot: CapabilitySnapshot
    ): WorkflowDefinition? {
        if (snapshot.filteredWorkflowIds.isEmpty()) return null
        val normalized = input.normalized
        val matched = workflows.filter { candidate ->
            candidate.workflowId in snapshot.filteredWorkflowIds &&
                candidate.available &&
                (candidate.triggerExamples.any { normalized.contains(it) } || normalized.contains(candidate.name))
        }
        return matched.singleOrNull()
    }

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
                    capabilitySnapshot = snapshot
                )
            }
            is FastIntentMatchResult.Ambiguous -> {
                return toolDomainDecisionLegacy(
                    reasonCode = RouteReasonCode.L0_RULE_AMBIGUOUS,
                    input = input,
                    context = context,
                    domain = domain,
                    snapshot = snapshot
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
                    missingArguments = match.missing
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
                        capabilitySnapshot = snapshot
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
        missingArguments: List<String> = emptyList()
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
        capabilitySnapshot = snapshot
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
                capabilitySnapshot = snapshot
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
                capabilitySnapshot = snapshot
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
    }
}
