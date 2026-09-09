package net.hwyz.iov.vehicle.ivi.ivai.agent.domain

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ClimateAirflowObjectWords

/**
 * 座舱气流语义对象（IVI-IVAI-DSN-CR-018）。
 *
 * 区分空调电源（HVAC_SYSTEM）、风口开关（VENT）、风量/风速档位（FAN_SPEED）、
 * 出风方向模式（AIRFLOW_DIRECTION）与自动空调（AUTO_HVAC）。对象证据只用于
 * 领域路由与 RAG/Prompt 的相似 Tool 区分，**不直接选择最终 Tool**（最终 Tool
 * 必须经 L0 确定性规则或受控 L1 候选链路选择）。
 *
 * 名称与 tool-registry [ClimateAirflowObjectWords] 的对象名对齐（单一事实源）。
 */
enum class SemanticObject {
    HVAC_SYSTEM,
    VENT,
    FAN_SPEED,
    AIRFLOW_DIRECTION,
    AUTO_HVAC
}

/**
 * 领域语义条目（IVI-IVAI-DSN-CR-018）。
 *
 * [phrase] 为治理词（同源）：Domain Router、确定性规则与模型 Prompt 禁止分别
 * 维护词表，均从本 Lexicon 生成。
 */
data class DomainSemanticEntry(
    val phrase: String,
    val domainId: BusinessDomainId,
    val capabilityPackId: String,
    val semanticObject: SemanticObject,
    val confidence: Double,
    val governanceVersion: String
)

/**
 * 座舱气流语义词典（IVI-IVAI-DSN-CR-018，治理单一事实源）。
 *
 * P0 词汇：空调、空调系统、空调电源、出风、吹风、送风、风口、通风口、出风口、
 * 风量、风速、气流、自动空调、AUTO 模式。补充 AIRFLOW_DIRECTION 表达（出风模式、
 * 风向、吹脸/吹脚/除霜/混合）——延续 CR-012 领域词表，统一到同一对象证据源。
 *
 * 只输出 Domain / Capability Pack / 语义对象证据，不选择 Tool；“吹风/出风”
 * 不得单独作为任一 Tool 的确定性正例。
 */
object CabinAirflowSemanticLexicon {

    /** 治理版本（写入请求级诊断，随词表内容变化而升级）。 */
    const val GOVERNANCE_VERSION = "ivai-airflow-lexicon-v1"

    /** 座舱气流对象所属 Capability Pack。 */
    const val CABIN_CLIMATE_PACK = "cabin.climate"

    private val CABIN = BusinessDomainId.CABIN_COMFORT

    /** 全部治理条目（P0 + AIRFLOW_DIRECTION 补充表达），从治理对象词表生成（单一事实源）。 */
    val ENTRIES: List<DomainSemanticEntry> = ClimateAirflowObjectWords.OBJECT_WORDS
        .flatMap { (objectName, words) ->
            words.map { word ->
                DomainSemanticEntry(
                    phrase = word,
                    domainId = CABIN,
                    capabilityPackId = CABIN_CLIMATE_PACK,
                    semanticObject = SemanticObject.valueOf(objectName),
                    confidence = 1.0,
                    governanceVersion = GOVERNANCE_VERSION
                )
            }
        }
        .sortedByDescending { it.phrase.length }
        .toList()

    /** 全部短语（小写，供领域打分合并；更长的词优先避免被短词遮蔽）。 */
    val PHRASES: List<String> = ENTRIES.map { it.phrase.lowercase() }

    /** 命中文本的语义对象集合（去重）。 */
    fun evidenceFor(normalized: String): Set<SemanticObject> =
        entriesFor(normalized).map { it.semanticObject }.toSet()

    /** 命中文本的治理条目（保留词表声明顺序）。 */
    fun entriesFor(normalized: String): List<DomainSemanticEntry> {
        val text = normalized.lowercase()
        return ENTRIES.filter { text.contains(it.phrase) }
    }

    /** 是否存在座舱气流对象证据。 */
    fun hasAirflowEvidence(normalized: String): Boolean = evidenceFor(normalized).isNotEmpty()

    /** 文本命中的短语（按词表顺序）。 */
    fun matchedPhrases(normalized: String): List<String> {
        val text = normalized.lowercase()
        return ENTRIES.filter { text.contains(it.phrase) }.map { it.phrase }
    }
}
