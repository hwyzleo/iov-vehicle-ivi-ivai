package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.RetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery

/**
 * Tiered intent router (IVI-IVAI-DSN-CR-005). Decides the execution tier for a
 * turn following the design order:
 *
 *  input normalization → safety / domain pre-check → L0 unique match?
 *    ├─ yes → ToolCallCandidate (no RAG / no LLM)
 *    └─ no → local tool domain? → L1 Tool/Intent RAG + local LLM
 *            └─ no → local knowledge domain? → L2 Knowledge RAG + local LLM
 *                    └─ no → L3 Cloud AI / REJECT
 *
 * The router records the hit tier, rule, confidence and reason code; retrieval
 * / prompt / model orchestration happens downstream in the workflow.
 */
class TieredIntentRouter(
    private val matcher: FastIntentMatcher,
    private val domainClassifier: DomainClassifier,
    private val safetyKeywords: List<String> = DEFAULT_SAFETY_KEYWORDS,
    private val knowledgeDomainEnabled: Boolean = true
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

        when (val match = matcher.match(input, context)) {
            is FastIntentMatchResult.Unique -> {
                return TieredRouteDecision(
                    tier = IntentTier.L0_DETERMINISTIC_TOOL,
                    confidence = match.confidence,
                    reasonCode = match.reasonCode,
                    directCandidate = match.candidate
                )
            }
            is FastIntentMatchResult.Ambiguous -> {
                return toolDomainDecision(
                    reasonCode = RouteReasonCode.L0_RULE_AMBIGUOUS,
                    input = input,
                    context = context
                )
            }
            is FastIntentMatchResult.MissingArguments -> {
                return toolDomainDecision(
                    reasonCode = RouteReasonCode.L0_MISSING_ARGUMENTS,
                    input = input,
                    context = context,
                    missingToolId = match.toolId,
                    missingArguments = match.missing
                )
            }
            FastIntentMatchResult.NoMatch -> {
                // 记录 L0 未命中的具体原因，便于可观测轨迹（L0 → L1）。
                val l0Reason = when {
                    input.hasNegation -> RouteReasonCode.L0_NEGATED
                    input.hasMultiIntent -> RouteReasonCode.L0_MULTI_INTENT
                    else -> RouteReasonCode.L0_NO_MATCH
                }
                return when (domainClassifier.classify(input)) {
                    IntentDomain.TOOL -> toolDomainDecision(
                        reasonCode = l0Reason,
                        input = input,
                        context = context
                    )
                    IntentDomain.KNOWLEDGE -> knowledgeDecision(input)
                    IntentDomain.OPEN -> TieredRouteDecision(
                        tier = IntentTier.L3_CLOUD_AI,
                        confidence = 0.5,
                        reasonCode = RouteReasonCode.L3_OPEN_DOMAIN
                    )
                }
            }
        }
    }

    private fun toolDomainDecision(
        reasonCode: String,
        input: NormalizedInput,
        context: AgentContext,
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
        missingArguments = missingArguments
    )

    private fun knowledgeDecision(input: NormalizedInput): TieredRouteDecision =
        if (!knowledgeDomainEnabled) {
            TieredRouteDecision(
                tier = IntentTier.L3_CLOUD_AI,
                confidence = 0.5,
                reasonCode = RouteReasonCode.L2_NO_KNOWLEDGE
            )
        } else {
            TieredRouteDecision(
                tier = IntentTier.L2_LOCAL_KNOWLEDGE,
                confidence = 0.7,
                reasonCode = RouteReasonCode.L2_KNOWLEDGE_DOMAIN,
                retrievalQuery = RetrievalQuery.Knowledge(
                    KnowledgeRetrievalQuery(text = input.normalized)
                )
            )
        }

    private companion object {
        /** Driving-safety control keywords — always REJECT (design pre-check). */
        val DEFAULT_SAFETY_KEYWORDS = listOf(
            "开车", "驾驶", "刹车", "制动", "转向", "方向盘", "油门", "加速",
            "挂挡", "开走", "变道", "倒车", "漂移", "自动驾驶", "车速"
        )
    }
}
