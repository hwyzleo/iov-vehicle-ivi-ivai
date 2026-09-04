package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry

/**
 * Domain classification used after L0 misses (CR-005 routing order):
 *  1. tool domain (车控) → L1 Tool/Intent RAG + local LLM
 *  2. knowledge domain (说明书/故障) → L2 Knowledge RAG + local LLM
 *  3. otherwise → L3 cloud / REJECT
 *
 * The tool vocabulary is built from the Tool Registry (names, positive
 * examples, deterministic phrases) so implicit expressions like "我有点冷"
 * classify as tool domain without hard-coding.
 */
enum class IntentDomain { TOOL, KNOWLEDGE, OPEN }

class DomainClassifier(
    private val registry: ToolRegistry,
    private val extraVocabulary: List<String> = DEFAULT_CAR_VOCAB,
    private val knowledgeSignals: List<String> = DEFAULT_KNOWLEDGE_SIGNALS
) {

    private val toolVocabulary: List<String> = buildList {
        addAll(extraVocabulary)
        for (tool in registry.all()) {
            add(tool.name)
            addAll(tool.positiveExamples)
            addAll(
                tool.description.split(Regex("[，。；、,.;：:\\s]+"))
                    .filter { it.length in 2..6 }
            )
            for (rule in tool.deterministicRules) {
                addAll(rule.exactPhrases)
            }
        }
    }.filter { it.isNotBlank() }.distinct()

    private val toolPatterns: List<Regex> = buildList {
        for (tool in registry.all()) {
            for (rule in tool.deterministicRules) {
                rule.synonymPatterns.forEach { pattern ->
                    runCatching { add(Regex(pattern)) }
                }
            }
        }
    }

    fun classify(input: NormalizedInput): IntentDomain {
        val normalized = input.normalized
        val hasToolSignal = toolVocabulary.any { normalized.contains(it) } ||
            toolPatterns.any { it.containsMatchIn(normalized) }
        if (hasToolSignal) return IntentDomain.TOOL
        val hasKnowledgeSignal = knowledgeSignals.any { normalized.contains(it) }
        if (hasKnowledgeSignal) return IntentDomain.KNOWLEDGE
        return IntentDomain.OPEN
    }

    companion object {
        /** Static car-control vocabulary + common implicit climate expressions. */
        private val DEFAULT_CAR_VOCAB = listOf(
            "空调", "温度", "暖风", "冷风", "风量", "风速", "吹风", "除霜", "除雾",
            "座椅", "通风", "循环", "车窗", "天窗", "氛围灯", "空气", "制热", "制冷",
            "有点冷", "太冷了", "有点热", "太热了", "凉一点", "热一点"
        )

        private val DEFAULT_KNOWLEDGE_SIGNALS = listOf(
            "是什么意思", "什么意思", "怎么回事", "怎么办", "怎么用", "如何使用",
            "怎么处理", "故障", "报警", "为什么", "是不是", "能否", "会不会", "保养"
        )
    }
}
