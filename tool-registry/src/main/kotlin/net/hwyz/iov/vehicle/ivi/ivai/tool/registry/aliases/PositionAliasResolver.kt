package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr017ErrorCodes

/**
 * 位置 Alias 解析器（IVI-IVAI-DSN-CR-017）。
 *
 * 唯一消费 [PositionAliasLexicon.vehicle_position_v2] 的请求级位置解析组件：
 *  - 仅提取证据（匹配的批准 Alias → canonical zone），不提前决定 Tool；
 *  - Alias 转换前先校验车型座舱拓扑（[VehicleCabinTopology]）；
 *  - 宽泛表达（右边/中间/后面/right/middle/back）不得静默映射 → 歧义；
 *  - 冲突/拓扑不适用输出 IVAI-ALIAS-AMBIGUOUS-001 / IVAI-ALIAS-TOPOLOGY-001。
 *
 * L0 Matcher、L1 Tool RAG、Prompt Candidate Context 与参数 canonicalization
 * 通过本解析器消费同一位置词表，防止语义漂移。
 */
data class MatchedAlias(
    /** 命中的原文词。 */
    val word: String,
    /** canonical zone。 */
    val canonicalValue: String,
    /** 命中的字段名（zone/position）。 */
    val fieldName: String,
    /** 原文命中区间。 */
    val evidenceRange: IntRange
)

/** 位置解析结果（证据 + 歧义/拓扑状态）。 */
data class PositionResolution(
    /** 命中的批准 Alias（证据，供 Candidate Context / 参数补齐）。 */
    val matchedEntries: List<MatchedAlias> = emptyList(),
    /** 命中的宽泛表达（歧义）。 */
    val ambiguousWords: List<String> = emptyList(),
    /** 命中但车型不适用（拓扑违规）。 */
    val topologyViolations: List<String> = emptyList()
) {
    /** 去重后的 canonical zone 证据。 */
    val matchedZones: Set<String> get() = matchedEntries.map { it.canonicalValue }.toSet()

    /** 存在歧义：宽泛表达命中或一对多 canonical。 */
    val hasAmbiguity: Boolean
        get() = ambiguousWords.isNotEmpty() || matchedZones.size > 1

    /** 存在拓扑违规。 */
    val hasTopologyViolation: Boolean
        get() = topologyViolations.isNotEmpty()

    /** 请求级错误码（无证据/无歧义/无违规时为 null）。 */
    val errorCode: String?
        get() = when {
            hasTopologyViolation -> Cr017ErrorCodes.ALIAS_TOPOLOGY
            hasAmbiguity -> Cr017ErrorCodes.ALIAS_AMBIGUOUS
            else -> null
        }

    /** 唯一 canonical 证据值（无歧义且非空时）。 */
    val singleZone: String?
        get() = if (matchedZones.size == 1) matchedZones.first() else null

    companion object {
        val EMPTY = PositionResolution()
    }
}

/**
 * 默认解析器：按 [PositionAliasLexicon] 最长优先匹配批准 Alias，随后扫描宽泛
 * 表达，并对命中做车型拓扑过滤。
 */
class PositionAliasResolver(
    private val topologyResolver: (String?) -> VehicleCabinTopology =
        { VehicleCabinTopology.forVehicle(it) }
) {

    fun resolve(query: String, vehicleModel: String? = null): PositionResolution {
        if (query.isBlank()) return PositionResolution.EMPTY
        val topology = topologyResolver(vehicleModel)
        val matched = mutableListOf<MatchedAlias>()
        val occupied = mutableListOf<IntRange>()
        val violations = mutableListOf<String>()

        // 最长优先匹配批准 Alias；已占用区间内的重叠词跳过（"第二排左" 覆盖 "第二排"）。
        for ((word, entry) in PositionAliasLexicon.WORDS) {
            var idx = query.indexOf(word)
            while (idx >= 0) {
                val range = idx..(idx + word.length - 1)
                if (occupied.none { it.overlaps(range) }) {
                    if (topology.supports(entry.canonicalValue)) {
                        matched += MatchedAlias(word, entry.canonicalValue, entry.fieldName, range)
                    } else {
                        violations += word
                    }
                    occupied += range
                    break
                }
                idx = query.indexOf(word, idx + 1)
            }
        }

        // 宽泛表达：命中即歧义（不得静默映射）。
        val ambiguous = PositionAliasLexicon.VAGUE_EXPRESSIONS
            .filter { query.contains(it) }
            .toList()

        return PositionResolution(
            matchedEntries = matched,
            ambiguousWords = ambiguous,
            topologyViolations = violations
        )
    }

    private fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last
}
