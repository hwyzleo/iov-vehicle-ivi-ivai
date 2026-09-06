package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotPattern
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolAvailabilityCheck
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionRange

/**
 * Default L0 matcher driven entirely by [net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule]
 * metadata from the Tool Definitions (CR-005) — no hard-coded if/else.
 *
 * Only a single tool with all required slots extracted, non-negated, non
 * multi-intent and available for the current vehicle / software version
 * produces [FastIntentMatchResult.Unique]. Everything else falls to L1 / ask /
 * reject.
 */
class DefaultFastIntentMatcher(
    private val registry: ToolRegistry,
    /** CR-008: 非空时只在指定 Tool ID 子集内匹配（Domain/Pack 收敛后的候选空间）。 */
    private val scopeToolIds: Set<String>? = null
) : FastIntentMatcher {

    override suspend fun match(input: NormalizedInput, context: AgentContext): FastIntentMatchResult {
        if (input.hasNegation) return FastIntentMatchResult.NoMatch
        if (input.hasMultiIntent) return FastIntentMatchResult.NoMatch

        val matches = mutableListOf<RuleMatch>()
        for (tool in registry.all()) {
            if (scopeToolIds != null && tool.toolId !in scopeToolIds) continue
            if (!ToolAvailabilityCheck.isAvailable(tool, context.vehicleModel, context.softwareVersion)) {
                continue
            }
            for (rule in tool.deterministicRules) {
                if (!versionAllowed(rule, context.softwareVersion)) continue
                if (ruleMatches(rule, input)) {
                    val slots = extractSlots(rule, input)
                    val missing = rule.slotPatterns
                        .filter { it.required && slots[it.name] == null }
                        .map { it.name }
                    matches += RuleMatch(tool, rule, slots, missing)
                }
            }
        }

        val distinctTools = matches.map { it.tool.toolId }.distinct()
        if (distinctTools.isEmpty()) return FastIntentMatchResult.NoMatch
        if (distinctTools.size > 1) {
            return FastIntentMatchResult.Ambiguous(
                matches.map { ToolCandidateRef(it.tool.toolId, it.rule.ruleId, confidence(it.rule, it.slots)) }
            )
        }

        val only = matches.first()
        return if (only.missing.isNotEmpty()) {
            FastIntentMatchResult.MissingArguments(only.tool.toolId, only.missing)
        } else {
            val confidence = confidence(only.rule, only.slots)
            FastIntentMatchResult.Unique(
                candidate = ToolCallCandidate(
                    requestId = context.requestId,
                    toolId = only.tool.toolId,
                    arguments = only.slots,
                    source = CandidateSource.L0_RULE,
                    confidence = confidence,
                    evidenceIds = listOf(only.rule.ruleId)
                ),
                confidence = confidence,
                reasonCode = RouteReasonCode.L0_UNIQUE_MATCH
            )
        }
    }

    private fun versionAllowed(rule: DeterministicIntentRule, softwareVersion: String?): Boolean {
        if (softwareVersion == null) return true
        val range = rule.applicableVersions
        val min = range.min
        val max = range.max
        if (min != null && VersionRange.compare(softwareVersion, min) < 0) return false
        if (max != null && VersionRange.compare(softwareVersion, max) > 0) return false
        return true
    }

    private fun ruleMatches(rule: DeterministicIntentRule, input: NormalizedInput): Boolean {
        val normalized = input.normalized
        if (rule.exactPhrases.any { normalized.contains(it) }) return true
        if (rule.synonymPatterns.any { Regex(it).containsMatchIn(normalized) }) return true
        return false
    }

    private fun extractSlots(rule: DeterministicIntentRule, input: NormalizedInput): Map<String, Any?> {
        val normalized = input.normalized
        val slots = LinkedHashMap<String, Any?>()
        for (slot in rule.slotPatterns) {
            val value = extractSlot(slot, normalized)
            if (value != null) slots[slot.name] = value
        }
        return slots
    }

    private fun extractSlot(slot: SlotPattern, normalized: String): Any? = when (slot.type) {
        SlotType.TEMPERATURE -> extractTemperature(normalized)
        SlotType.POSITION -> extractPosition(normalized, slot.aliases)
        SlotType.STEP -> extractStep(normalized)
        SlotType.NUMERIC -> extractNumber(normalized)
    }

    private fun extractTemperature(normalized: String): Double? {
        val withUnit = Regex("(\\d{1,3}(?:\\.\\d+)?)\\s*(?:度|℃|摄氏度)").find(normalized)
        if (withUnit != null) return withUnit.groupValues[1].toDoubleOrNull()
        val bare = Regex("(\\d{1,3}(?:\\.\\d+)?)").find(normalized)
        return bare?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun extractPosition(normalized: String, aliases: Map<String, String>): String? {
        val sorted = aliases.entries.sortedByDescending { it.key.length }
        for ((word, canonical) in sorted) {
            if (normalized.contains(word)) return canonical
        }
        return null
    }

    private fun extractStep(normalized: String): Int? {
        val withDang = Regex("(\\d+)\\s*档").find(normalized)
        if (withDang != null) return withDang.groupValues[1].toIntOrNull()
        return null
    }

    private fun extractNumber(normalized: String): Int? =
        Regex("(\\d+)").find(normalized)?.groupValues?.get(1)?.toIntOrNull()

    private fun confidence(rule: DeterministicIntentRule, slots: Map<String, Any?>): Double {
        val slotBonus = slots.size * 0.02
        return (rule.minConfidence + slotBonus).coerceAtMost(1.0)
    }

    private data class RuleMatch(
        val tool: ToolDefinition,
        val rule: DeterministicIntentRule,
        val slots: Map<String, Any?>,
        val missing: List<String>
    )
}
