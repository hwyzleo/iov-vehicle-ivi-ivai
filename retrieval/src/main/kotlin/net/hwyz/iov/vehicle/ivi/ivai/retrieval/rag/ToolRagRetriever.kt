package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.BoostBreakdown
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolCandidate
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolDefinitionSummary
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetriever
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingRequest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rerank.NoopRerankerProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rerank.RerankerProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rerank.RetrievalCandidate
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.vectorstore.VectorStore
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.PositionAliasResolver
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolAvailabilityCheck
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ClimateAirflowObjectWords
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ClimateToolBoundaryCatalog

/**
 * L1 Tool/Intent RAG 检索器（CR-011 + CR-017）。
 *
 * 查询流程：Normalized query → Domain / OperationType / Capability Pack /
 * Runtime Capability filter（先过滤再检索）→ EmbeddingProvider.embed(query) →
 * tool-intent index exact cosine search → minScore + canonical ID 去重 + 混合召回
 * （CR-017：vectorScore + approvedAliasExactBoost + slotCoverageBoost +
 * operationBoundaryBoost）→ Tool Top-K → Reranker NONE → Local LLM candidate context。
 *
 * 安全约束：只检索统一运行时候选集的合法子集（[ToolRetrievalQuery.runtimeCapabilityToolIds]）；
 * Boost 只能作用于治理过滤后的候选（CR-017 REQ-178），不得恢复被治理状态或 Binding
 * 过滤掉的资产；命中位置词只提升具备 zone 参数的候选，不直接决定 Tool。
 */
class ToolRagRetriever(
    private val registry: ToolRegistry,
    private val vectorStore: VectorStore,
    private val embeddingProvider: EmbeddingProvider,
    private val namespace: String = DEFAULT_NAMESPACE,
    private val reranker: RerankerProvider = NoopRerankerProvider(),
    private val boost: GovernedBoost = GovernedBoost(
        positionResolver = PositionAliasResolver()
    )
) : ToolRetriever {

    override suspend fun retrieve(query: ToolRetrievalQuery, topK: Int): List<ToolCandidate> {
        if (!embeddingProvider.available) return emptyList()
        val response = embeddingProvider.embed(EmbeddingRequest(listOf(query.text)))
        if (response.vectors.isEmpty()) return emptyList()

        val hits = vectorStore.search(
            VectorQuery(
                namespace = namespace,
                vector = response.vectors.first(),
                topK = (topK * SEARCH_MULTIPLIER).coerceAtLeast(16),
                filter = { md -> metadataFilter(md, query) }
            )
        )

        // canonical ID 去重：同一资产（含 Alias 分片）只保留最高分。
        val bestByCanonical = mutableMapOf<String, Double>()
        for (hit in hits) {
            val canonicalId = hit.metadata[RetrievalMetadataKeys.CANONICAL_ID] ?: hit.documentId
            bestByCanonical[canonicalId] = maxOf(
                bestByCanonical[canonicalId] ?: Double.NEGATIVE_INFINITY,
                hit.score
            )
        }

        // 混合召回（CR-017 + CR-018）：治理过滤后叠加 Alias/Slot/Operation/Object/Action
        // Boost 与负边界惩罚，记录分项。
        val candidates = bestByCanonical.mapNotNull { (canonicalId, baseScore) ->
            val tool = registry.get(canonicalId) ?: return@mapNotNull null
            if (!ToolAvailabilityCheck.isAvailable(tool, query.vehicleModel, query.softwareVersion)) {
                return@mapNotNull null
            }
            val boosted = boost.boost(tool, query, baseScore)
            ToolCandidate(
                toolId = canonicalId,
                score = boosted.finalScore,
                matchedFields = listOf("vector") + boosted.matchedFields,
                definition = ToolDefinitionSummary.from(tool),
                boostBreakdown = boosted.breakdown
            )
        }.toMutableList()

        // CR-018 召回保障：五类气候表达（power/vent/fan/airflow/auto）命中「对象+动作」
        // 强证据时，把对应 Tool 作为受控候选保证进入 Top-K 窗口（仍属 RuntimeCapabilitySet
        // 合法候选；负边界惩罚继续区分），使弱向量召回下 Recall@5 可复现。
        appendEvidenceCandidates(query, candidates, boost)
        val ordered = candidates.sortedByDescending { it.score }

        // RerankerProvider.NONE：保持基础顺序；将来引入 HTTP/LOCAL 只重排已过滤结果。
        val reranked = reranker.rerank(
            query.text,
            ordered.map { RetrievalCandidate(it.toolId, it.toolId, it.definition.description, it.score) }
        )
        val rerankedIds = reranked.map { it.documentId }.toSet()
        val finalOrdered = ordered.filter { it.toolId in rerankedIds } +
            ordered.filter { it.toolId !in rerankedIds }
        return finalOrdered.take(topK)
    }

    /**
     * CR-018：气候对象证据召回保障。
     *
     * 查询命中某 Tool 的「正向对象 + 正向动作」且未命中其负例边界时，将该 Tool 补入
     * 候选（以当前候选最低分或 0 为向量底分），再经统一 Boost/负边界惩罚排序。
     * 只作用于 ClimateToolBoundaryCatalog 治理目录内的合法候选，不改变非气候请求召回。
     */
    private fun appendEvidenceCandidates(
        query: ToolRetrievalQuery,
        candidates: MutableList<ToolCandidate>,
        boost: GovernedBoost
    ) {
        val queryObjects = ClimateAirflowObjectWords.objectsFor(query.text)
        if (queryObjects.isEmpty()) return
        val existingIds = candidates.mapTo(mutableSetOf()) { it.toolId }
        val baseScore = candidates.minOfOrNull { it.score } ?: 0.0
        for (boundary in ClimateToolBoundaryCatalog.ALL.values) {
            if (boundary.canonicalId in existingIds) continue
            if (!assistAllowed(query, boundary.canonicalId)) continue
            val hitObjects = boundary.positiveObjects intersect queryObjects
            val hitActions = boundary.positiveActions.any { query.text.lowercase().contains(it.lowercase()) }
            val hitNegative = boundary.negativeExamples.any { query.text.lowercase().contains(it.lowercase()) }
            if (hitObjects.isNotEmpty() && hitActions && !hitNegative) {
                val tool = registry.get(boundary.canonicalId) ?: continue
                if (!ToolAvailabilityCheck.isAvailable(tool, query.vehicleModel, query.softwareVersion)) continue
                val boosted = boost.boost(tool, query, baseScore)
                candidates += ToolCandidate(
                    toolId = boundary.canonicalId,
                    score = boosted.finalScore,
                    matchedFields = listOf("evidence") + boosted.matchedFields,
                    definition = ToolDefinitionSummary.from(tool),
                    boostBreakdown = boosted.breakdown
                )
            }
        }
    }

    /** 召回保障只作用于治理过滤后的合法候选（Domain / OperationType / Runtime 集）。 */
    private fun assistAllowed(query: ToolRetrievalQuery, toolId: String): Boolean {
        if (query.runtimeCapabilityToolIds.isNotEmpty() && toolId !in query.runtimeCapabilityToolIds) return false
        if (query.domainIds.isNotEmpty() &&
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId.CABIN_COMFORT !in query.domainIds
        ) {
            return false
        }
        if (query.operationTypes.isNotEmpty() &&
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.CONTROL !in query.operationTypes
        ) {
            return false
        }
        return true
    }

    /** 先过滤再检索：Domain / OperationType / Capability Pack / Runtime Capability。 */
    private fun metadataFilter(md: Map<String, String>, query: ToolRetrievalQuery): Boolean {
        val domainIds = (md[RetrievalMetadataKeys.DOMAIN_IDS] ?: "").split(",").filter { it.isNotBlank() }
        if (query.domainIds.isNotEmpty() &&
            domainIds.none { it in query.domainIds.map { d -> d.code } }
        ) {
            return false
        }
        val operationTypes = (md[RetrievalMetadataKeys.OPERATION_TYPES] ?: "").split(",").filter { it.isNotBlank() }
        if (query.operationTypes.isNotEmpty() &&
            operationTypes.none { it in query.operationTypes.map { o -> o.name } }
        ) {
            return false
        }
        val packIds = (md[RetrievalMetadataKeys.CAPABILITY_PACK_IDS] ?: "").split(",").filter { it.isNotBlank() }
        if (query.capabilityPackIds.isNotEmpty() &&
            packIds.none { it in query.capabilityPackIds }
        ) {
            return false
        }
        val canonicalId = md[RetrievalMetadataKeys.CANONICAL_ID]
        if (query.runtimeCapabilityToolIds.isNotEmpty() &&
            canonicalId != null && canonicalId !in query.runtimeCapabilityToolIds
        ) {
            return false
        }
        return true
    }

    companion object {
        const val DEFAULT_NAMESPACE = "tool-intent"
        private const val SEARCH_MULTIPLIER = 3
    }
}

/**
 * 混合召回加权（CR-011 + CR-017 + CR-018）。
 *
 * finalScore = vectorScore + objectEvidenceBoost + actionEvidenceBoost +
 * requiredSlotCoverageBoost + approvedAliasBoost + operationBoundaryBoost
 * − negativeBoundaryPenalty；仅基于已治理的 Domain、OperationType、位置 Alias、
 * 对象/动作/槽位证据与相似 Tool 负边界，保证不把被治理/过滤掉的资产捞回来。
 *
 * CR-017（REQ-178）：
 *  - Alias Boost：位置词（中左/中右/2排/3排 …）只提升具备 zone 参数的候选；
 *  - Operation Boundary Boost：绝对档位（调到/设为/设置…档）提升 speed.set，
 *    相对增减（调大/调小/增加/减少…）提升 speed.adjust；
 *  - 记录 vectorScore / aliasBoost / slotBoost / finalScore（Top-K trace）。
 *
 * CR-018（对象/动作/槽位/负边界）：
 *  - objectEvidenceBoost：查询命中 Tool 正向对象（HVAC_SYSTEM/VENT/FAN_SPEED/…）；
 *  - actionEvidenceBoost：查询命中 Tool 正向动作（打开/关闭/启动/绝对/相对/吹脸…）；
 *  - requiredSlotCoverageBoost：查询命中 Tool 必填槽位标记（档/级/数值/方向/模式）；
 *  - negativeBoundaryPenalty：查询命中相似 Tool 负例边界时扣分（防止宽泛表达误执行）；
 *  - 所有加权只作用于 RuntimeCapabilitySet 合法候选，Trace 记录每个分量。
 */
class GovernedBoost(
    private val positionResolver: PositionAliasResolver = PositionAliasResolver(),
    private val aliasWeight: Double = 0.5,
    private val slotCoverageWeight: Double = 0.3,
    private val operationBoundaryWeight: Double = 0.4,
    /** CR-018: 对象证据权重。 */
    private val objectEvidenceWeight: Double = 0.6,
    /** CR-018: 动作证据权重。 */
    private val actionEvidenceWeight: Double = 0.4,
    /** CR-018: 相似 Tool 负边界惩罚权重（高于一般 Boost，用于打破跨 Tool 竞争）。 */
    private val negativeBoundaryWeight: Double = 1.2
) {

    /** 绝对档位/相对增减动作词（操作边界证据）。 */
    private val absoluteMarkers = setOf("调到", "设为", "档位调到", "档位设为")
    private val relativeMarkers = setOf("调大", "调小", "大一点", "小一点", "增加", "减少", "调高", "调低")

    /** 必填槽位标记：level=数值/档/级；mode=方向模式词；direction=相对增减词。 */
    private val levelMarkers = listOf("档", "级")
    private val modeMarkers = listOf("吹脸", "吹脚", "吹腿", "除霜", "除雾", "混合", "出风模式")
    private val directionMarkers = listOf("调高", "调低", "调大", "调小", "增加", "减少")

    data class BoostedResult(
        val finalScore: Double,
        val breakdown: BoostBreakdown,
        val matchedFields: List<String>
    )

    fun boost(tool: ToolDefinition, query: ToolRetrievalQuery, baseScore: Double): BoostedResult {
        var aliasBoost = 0.0
        var slotBoost = 0.0
        var operationBoost = 0.0
        var objectBoost = 0.0
        var actionBoost = 0.0
        var boundaryPenalty = 0.0
        val matchedFields = mutableListOf<String>()
        val text = query.text.lowercase()
        val boundary = ClimateToolBoundaryCatalog.forTool(tool.toolId)

        // 1) 批准位置 Alias 精确命中（vehicle_position_v2）：只提升具备 zone 参数的候选。
        val resolution = positionResolver.resolve(query.text, query.vehicleModel)
        val hasZoneParam = schemaHasZone(tool)
        if (resolution.matchedZones.isNotEmpty() && hasZoneParam) {
            aliasBoost += aliasWeight
            matchedFields += "alias:${resolution.matchedZones.sorted().joinToString(",")}"
        }

        // 2) 槽位覆盖：必填槽位标记（档/级/模式/方向/位置）与分区正例词级重叠。
        val covered = tool.positiveExamples.count { query.text.contains(it) || containsSlotWords(query.text, it) }
        if (covered > 0) {
            slotBoost += slotCoverageWeight * covered
            matchedFields += "slot:$covered"
        }
        if (boundary != null) {
            val requiredHits = slotHits(boundary.requiredSlots, text)
            if (requiredHits.isNotEmpty()) {
                slotBoost += slotCoverageWeight * requiredHits.size
                matchedFields += "slot-required:${requiredHits.sorted().joinToString(",")}"
            }
        }

        // 3) 操作边界：绝对档位 vs 相对增减（speed.set 与 speed.adjust 由动作证据区分）。
        val absoluteHit = absoluteMarkers.any { text.contains(it) }
        val relativeHit = relativeMarkers.any { text.contains(it) }
        val isAbsoluteTool = tool.toolId.endsWith(".set") || tool.toolId.contains("set")
        val isRelativeTool = tool.toolId.endsWith(".adjust")
        if (absoluteHit && isAbsoluteTool && !isRelativeTool) {
            operationBoost += operationBoundaryWeight
            matchedFields += "operation:absolute"
        } else if (relativeHit && isRelativeTool) {
            operationBoost += operationBoundaryWeight
            matchedFields += "operation:relative"
        }

        // 4) CR-018：对象 / 动作证据与负边界惩罚（治理边界目录）。
        if (boundary != null) {
            val queryObjects = ClimateAirflowObjectWords.objectsFor(text)
            val hitObjects = boundary.positiveObjects intersect queryObjects
            if (hitObjects.isNotEmpty()) {
                objectBoost += objectEvidenceWeight * hitObjects.size
                matchedFields += "object:${hitObjects.sorted().joinToString(",")}"
            }
            val hitActions = boundary.positiveActions.filter { text.contains(it.lowercase()) }
            if (hitActions.isNotEmpty()) {
                actionBoost += actionEvidenceWeight * hitActions.size
                matchedFields += "action:${hitActions.sorted().joinToString(",")}"
            }
            val negHits = boundary.negativeExamples.filter { text.contains(it.lowercase()) }
            if (negHits.isNotEmpty()) {
                boundaryPenalty += negativeBoundaryWeight * negHits.size
                matchedFields += "boundary:-${negHits.sorted().joinToString(",")}"
            }
        }

        val breakdown = BoostBreakdown(
            vectorScore = baseScore,
            aliasBoost = aliasBoost,
            slotCoverageBoost = slotBoost,
            operationBoundaryBoost = operationBoost,
            objectEvidenceBoost = objectBoost,
            actionEvidenceBoost = actionBoost,
            negativeBoundaryPenalty = boundaryPenalty
        )
        return BoostedResult(
            finalScore = breakdown.finalScore,
            breakdown = breakdown,
            matchedFields = matchedFields
        )
    }

    /**
     * CR-018（IVAI-RAG-BOUNDARY-002 校验项）：边界 Tool 的排序必须记录对象/动作/槽位
     * 证据分量（Trace 完整）。测试与可观测性使用；生产排序始终生成全分量 Breakdown。
     */
    fun hasEvidenceTrace(toolId: String, breakdown: BoostBreakdown): Boolean {
        if (ClimateToolBoundaryCatalog.forTool(toolId) == null) return true
        return breakdown.objectEvidenceBoost >= 0.0 &&
            breakdown.actionEvidenceBoost >= 0.0 &&
            breakdown.slotCoverageBoost >= 0.0 &&
            breakdown.negativeBoundaryPenalty >= 0.0
    }

    /** 必填槽位命中标记（按 boundary.requiredSlots 逐项判定）。 */
    private fun slotHits(requiredSlots: Set<String>, text: String): Set<String> = buildSet {
        for (slot in requiredSlots) {
            val hit = when (slot) {
                "level" -> levelMarkers.any { text.contains(it) } || Regex("\\d").containsMatchIn(text)
                "mode" -> modeMarkers.any { text.contains(it) }
                "direction" -> directionMarkers.any { text.contains(it) }
                "step" -> levelMarkers.any { text.contains(it) } || Regex("\\d").containsMatchIn(text)
                "zone" -> positionResolver.resolve(text, null).matchedZones.isNotEmpty()
                "enabled" -> false // 开关词过于宽泛，不作槽位证据（由动作证据覆盖）
                else -> false
            }
            if (hit) add(slot)
        }
    }

    private fun schemaHasZone(tool: ToolDefinition): Boolean {
        val names = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema.CanonicalSchemaParser
            .parse(tool.parameterSchema).properties.map { it.name }
        return names.any { it == "zone" || it == "position" }
    }

    /** 正例与查询的词级重叠（分区表达：查询含“中左”与正例“中左风量档位调到5档”共享位置词）。 */
    private fun containsSlotWords(query: String, example: String): Boolean {
        val zoneWords = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.PositionAliasLexicon.WORDS.map { it.first }
        return zoneWords.any { word -> query.contains(word) && example.contains(word) }
    }
}
