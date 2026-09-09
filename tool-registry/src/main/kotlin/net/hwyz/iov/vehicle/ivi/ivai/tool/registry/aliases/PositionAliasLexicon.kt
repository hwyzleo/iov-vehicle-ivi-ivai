package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases

/**
 * 版本化字段级位置 Alias 词典（IVI-IVAI-DSN-CR-017，vehicle_position_v2）。
 *
 * 版本化 Position Alias 是位置语义的唯一事实源，L0 Matcher、L1 Tool RAG、
 * ParameterCanonicalizationService 与测试评分共同消费；禁止在 Prompt、Retriever
 * 和评分器中分别维护位置词表。
 *
 * 约束（REQ-171～174）：
 *  - Alias 必须先经车型座舱拓扑过滤（[VehicleCabinTopology]）；车型不存在三排时，
 *    不得把 "3排" 转换为可执行参数（IVAI-ALIAS-TOPOLOGY-001）；
 *  - "右边"/"中间"/"后面" 等宽泛表达不得静默映射为具体枚举（[VAGUE_EXPRESSIONS]），
 *    存在多个合法解释时进入 L1 消歧或追问（IVAI-ALIAS-AMBIGUOUS-001）；
 *  - right 不得默认映射为 middle_right；rear 也不得替代明确的 second_row/third_row。
 */
enum class AliasAmbiguityPolicy {
    /** 唯一 canonical 映射（无歧义）。 */
    UNIQUE,

    /** 依赖车型座舱拓扑（是否存在对应排/区）。 */
    TOPOLOGY_DEPENDENT
}

/**
 * 字段级 Alias 条目（设计正文数据契约）。
 *
 * [targetId] 为可选的目标 Tool（当前位置 Alias 为字段级，targetId 置空）；
 * [vehicleScope] 保留车型约束扩展位（当前拓扑校验走 [VehicleCabinTopology]）。
 */
data class FieldAliasEntry(
    val lexiconId: String,
    val targetId: String?,
    val fieldName: String,
    val canonicalValue: String,
    val aliases: Set<String>,
    val vehicleScope: String? = null,
    val ambiguityPolicy: AliasAmbiguityPolicy,
    val governanceVersion: String
)

/** vehicle_position_v2 词典（CR-017 P0 基线 9 个 canonical zone）。 */
object PositionAliasLexicon {

    const val LEXICON_ID = "vehicle_position_v2"
    const val GOVERNANCE_VERSION = "ivai-position-aliases-v2"

    /** 批准 Alias 条目（canonical zone → 批准 Alias 集合）。 */
    val ENTRIES: List<FieldAliasEntry> = listOf(
        FieldAliasEntry(
            lexiconId = "$LEXICON_ID.all", targetId = null, fieldName = "zone", canonicalValue = "all",
            aliases = setOf("全部", "所有", "整车", "全车", "ALL", "ALL_ZONES", "whole_vehicle"),
            ambiguityPolicy = AliasAmbiguityPolicy.UNIQUE, governanceVersion = GOVERNANCE_VERSION
        ),
        FieldAliasEntry(
            lexiconId = "$LEXICON_ID.driver", targetId = null, fieldName = "zone", canonicalValue = "driver",
            aliases = setOf("主驾", "驾驶位", "司机位", "主驾驶"),
            ambiguityPolicy = AliasAmbiguityPolicy.UNIQUE, governanceVersion = GOVERNANCE_VERSION
        ),
        FieldAliasEntry(
            lexiconId = "$LEXICON_ID.passenger", targetId = null, fieldName = "zone", canonicalValue = "passenger",
            aliases = setOf("副驾", "副驾驶", "副驾驶位", "乘客位"),
            ambiguityPolicy = AliasAmbiguityPolicy.UNIQUE, governanceVersion = GOVERNANCE_VERSION
        ),
        FieldAliasEntry(
            lexiconId = "$LEXICON_ID.front", targetId = null, fieldName = "zone", canonicalValue = "front",
            aliases = setOf("前排", "第一排", "1排"),
            ambiguityPolicy = AliasAmbiguityPolicy.UNIQUE, governanceVersion = GOVERNANCE_VERSION
        ),
        FieldAliasEntry(
            lexiconId = "$LEXICON_ID.rear", targetId = null, fieldName = "zone", canonicalValue = "rear",
            aliases = setOf("后排"),
            ambiguityPolicy = AliasAmbiguityPolicy.UNIQUE, governanceVersion = GOVERNANCE_VERSION
        ),
        FieldAliasEntry(
            lexiconId = "$LEXICON_ID.middle_left", targetId = null, fieldName = "zone", canonicalValue = "middle_left",
            aliases = setOf("中左", "中排左", "第二排左", "2排左"),
            ambiguityPolicy = AliasAmbiguityPolicy.TOPOLOGY_DEPENDENT, governanceVersion = GOVERNANCE_VERSION
        ),
        FieldAliasEntry(
            lexiconId = "$LEXICON_ID.middle_right", targetId = null, fieldName = "zone", canonicalValue = "middle_right",
            aliases = setOf("中右", "中排右", "第二排右", "2排右"),
            ambiguityPolicy = AliasAmbiguityPolicy.TOPOLOGY_DEPENDENT, governanceVersion = GOVERNANCE_VERSION
        ),
        FieldAliasEntry(
            lexiconId = "$LEXICON_ID.second_row", targetId = null, fieldName = "zone", canonicalValue = "second_row",
            aliases = setOf("二排", "第二排", "2排", "ROW2", "ZONE2"),
            ambiguityPolicy = AliasAmbiguityPolicy.TOPOLOGY_DEPENDENT, governanceVersion = GOVERNANCE_VERSION
        ),
        FieldAliasEntry(
            lexiconId = "$LEXICON_ID.third_row", targetId = null, fieldName = "zone", canonicalValue = "third_row",
            aliases = setOf("三排", "第三排", "3排", "ROW3", "3RD"),
            ambiguityPolicy = AliasAmbiguityPolicy.TOPOLOGY_DEPENDENT, governanceVersion = GOVERNANCE_VERSION
        )
    )

    /**
     * 宽泛表达：不得静默映射为具体枚举（REQ-174）。
     * 命中即进入 L1 消歧或追问（IVAI-ALIAS-AMBIGUOUS-001）。
     */
    val VAGUE_EXPRESSIONS: Set<String> = setOf(
        "右边", "左边", "中间", "后面", "前面",
        "right", "middle", "back"
    )

    /** word → 所属条目（按词长降序，供最长优先匹配）。 */
    val WORDS: List<Pair<String, FieldAliasEntry>> by lazy {
        ENTRIES.flatMap { entry ->
            entry.aliases.map { it to entry }
        }.sortedByDescending { it.first.length }
    }

    /** canonical zone 全量枚举（Schema 展开 / Candidate Context 使用）。 */
    val CANONICAL_ZONES: List<String> = ENTRIES.map { it.canonicalValue }

    /** canonical zone → 批准 Alias（RAG 文档 / Boost 使用）。 */
    val APPROVED_ALIASES: Map<String, Set<String>> = ENTRIES.associate { entry ->
        entry.canonicalValue to entry.aliases
    }

    /** 条目按 canonical 值索引。 */
    val BY_CANONICAL: Map<String, FieldAliasEntry> = ENTRIES.associateBy { it.canonicalValue }

    /** word → canonical 映射（与 L0AliasVocabularies.vehicle_position_v2 同源生成，防漂移）。 */
    val WORD_TO_CANONICAL: Map<String, String> = WORDS.map { (word, entry) -> word to entry.canonicalValue }.toMap()
}
