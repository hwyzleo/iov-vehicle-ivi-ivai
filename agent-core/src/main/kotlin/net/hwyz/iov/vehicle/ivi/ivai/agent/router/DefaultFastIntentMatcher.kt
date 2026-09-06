package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotPattern
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolAvailabilityCheck
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionRange

/**
 * 通用确定性匹配器（IVI-IVAI-DSN-CR-005 + CR-010）。
 *
 * CR-010：FastIntentMatcher 是通用解析器——不硬编码业务短语，也不为某批 Tool
 * 建立专用分支；它只消费每个 Tool 的 DeterministicMatchProfile
 * （exactPhrases / synonymPatterns / slotPatterns / negativePatterns / priority /
 * applicableVersions）。当且仅当：
 *  - 唯一 canonical Tool；
 *  - 必填参数可由文本、Alias 预置参数或上下文完整确定；
 *  - 无否定、多意图和同优先级冲突；
 *  - 当前车型、软件版本、治理状态与 Binding 有效；
 *  - Policy 允许直接生成候选；
 * 才返回 [FastIntentMatchResult.Unique]（L0，省略 Retrieval 与 LLM，但不省略
 * Schema / Availability / Policy / 确认 / 幂等 / 执行链路）。
 *
 * 候选范围由调用方通过 [scopeToolIds] 限定为统一 runtimeCandidateToolIds。
 */
class DefaultFastIntentMatcher(
    private val registry: ToolRegistry,
    /** CR-008/CR-010：非空时只在指定统一 canonical 候选集内匹配。 */
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
            // CR-010：确定性匹配产生冲突，不能唯一确定 Tool（IVAI-ROUTE-003）。
            return FastIntentMatchResult.Ambiguous(
                matches.map { ToolCandidateRef(it.tool.toolId, it.rule.ruleId, confidence(it.rule, it.slots)) },
                matchedToolIds = distinctTools.toSet()
            )
        }

        // 同一 canonical Tool 命中多条规则（如“主驾升温”命中通用 up 规则 + 表达 Alias 规则）：
        // 选取优先级最高、槽位最完整、缺参最少的规则（表达 Alias 预置 zone/direction，优于通用规则）。
        val only = matches
            .filter { it.tool.toolId == distinctTools.first() }
            .maxWithOrNull(
                compareBy<RuleMatch> { it.rule.priority }
                    .thenBy { it.slots.size }
                    .thenByDescending { it.missing.size }
            )!!
        // Policy 允许直达：需要确认的 Tool 不得跳过 Retrieval/LLM（交由 L1 处理确认）。
        if (only.tool.policy.requiresConfirmation) {
            return FastIntentMatchResult.NoMatch
        }
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
                reasonCode = RouteReasonCode.L0_UNIQUE_MATCH,
                matchedPatternId = only.rule.ruleId,
                canonicalToolId = only.tool.toolId,
                matchedToolIds = setOf(only.tool.toolId)
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
        // CR-010：negativePatterns 命中任一 → 规则被否定（如“关闭空调”否定开机规则）。
        if (rule.negativePatterns.any { normalized.contains(it) }) return false
        if (rule.exactPhrases.any { normalized.contains(it) }) return true
        if (rule.synonymPatterns.any { Regex(it).containsMatchIn(normalized) }) return true
        return false
    }

    private fun extractSlots(rule: DeterministicIntentRule, input: NormalizedInput): Map<String, Any?> {
        val normalized = input.normalized
        // CR-010：Alias 预置参数（“打开空调”→ enabled=true；“升温”→ direction=increase）
        // 提供无法从文本确定的必填参数；文本抽取的槽位优先覆盖。
        val slots = LinkedHashMap<String, Any?>(rule.presetArguments)
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

    /**
     * 步进槽位抽取（CR-005 + CR-010 覆盖补齐）：
     *  - 阿拉伯数字 + 单位（档 / 度 / ℃ / 摄氏度）："调高2度"、"2档" → 2
     *  - 中文数字 + 单位："两度" → 2、"二档" → 2、"三度" → 3
     *  - 值域对齐 adjust Schema（step:[0.5..5]），越界值不作为步进（如
     *    "温度调高到26度" 是绝对设定而非相对步进）。
     */
    private fun extractStep(normalized: String): Int? {
        val arabic = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:档|度|℃|摄氏度)").find(normalized)
        if (arabic != null) {
            val v = arabic.groupValues[1].toDoubleOrNull()?.toInt()
            if (v != null && v in 1..5) return v
        }
        val chinese = Regex("([零一二两三四五六七八九十]{1,3})\\s*(?:档|度|℃|摄氏度)").find(normalized)
        if (chinese != null) {
            val v = chineseNumber(chinese.groupValues[1])
            if (v != null && v in 1..5) return v
        }
        return null
    }

    /** 中文数字 1~99 转换（一/两/二/三…十/十一/二十/二十五）。 */
    private fun chineseNumber(s: String): Int? {
        if (s.isEmpty()) return null
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (s == "十") return 10
        if (s.length == 1) return digits[s[0]]
        val idx = s.indexOf('十')
        if (idx < 0) return null
        val tens = if (idx == 0) 1 else digits[s[0]] ?: return null
        val ones = if (s.length > idx + 1) digits[s[idx + 1]] ?: 0 else 0
        return tens * 10 + ones
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
