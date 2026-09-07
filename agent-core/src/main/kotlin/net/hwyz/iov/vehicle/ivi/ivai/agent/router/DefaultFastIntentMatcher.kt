package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotPattern
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolAvailabilityCheck
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionRange

/**
 * 通用确定性匹配器（IVI-IVAI-DSN-CR-005 + CR-010 + CR-013）。
 *
 * CR-013：FastIntentMatcher 只消费 Catalog 生成的 DeterministicIntentRule，
 * 不按 Tool ID 分支。当且仅当：
 *  - 输入无全局否定 / 多意图（NormalizedInput 预判）；
 *  - 规则短语命中后通过对象/动作/OperationType 兼容与导航/查询意图冲突防护
 *    （“打开空调设置页面”不得命中空调电源控制）；
 *  - 参数合并遵循「显式槽位 > Alias 映射 > 规则预置 > Schema 默认值」，显式槽位
 *    与预置矛盾时返回 [FastIntentMatchResult.ArgumentConflict]（IVAI-ROUTE-005），
 *    不以优先级静默覆盖；
 *  - 唯一 canonical Tool；必填参数完整；同 Tool 内按 priority + 槽位完整度取最优；
 *  - 跨 Tool/Workflow 存在多个不可等价候选 → [FastIntentMatchResult.Ambiguous]
 *    （IVAI-ROUTE-003），priority 不得静默决胜不同 canonical；
 *  - 当前车型、软件版本、治理状态与 Binding 有效；Policy 允许直接生成候选；
 * 才返回 [FastIntentMatchResult.Unique]（L0，不省略 Schema / Availability /
 * Policy / 确认 / 幂等 / 执行链路）。
 *
 * 候选范围由调用方通过 [scopeToolIds] 限定为 deterministicCandidateToolIds
 * （CR-013：SUPPORTED + Profile APPROVED + 版本/Hash 有效的治理投影）。
 */
class DefaultFastIntentMatcher(
    private val registry: ToolRegistry,
    /** CR-008/CR-010/CR-013：非空时只在指定统一确定性候选集内匹配。 */
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
                if (!ruleMatches(rule, input)) continue
                // CR-013：短语命中 ≠ 可执行；导航/查询/配置意图冲突直接拦截。
                if (intentTypeConflict(rule, tool, input)) continue
                // CR-013：参数合并（显式槽位 > 规则预置），矛盾 → IVAI-ROUTE-005。
                val merge = mergeArguments(rule, input)
                if (merge.conflict != null) {
                    return FastIntentMatchResult.ArgumentConflict(
                        toolId = tool.toolId,
                        conflictingArgument = merge.conflict,
                        sources = merge.sources
                    )
                }
                val missing = rule.slotPatterns
                    .filter { it.required && merge.arguments[it.name] == null }
                    .map { it.name }
                matches += RuleMatch(tool, rule, merge.arguments, missing, merge.sources)
            }
        }

        val distinctTools = matches.map { it.tool.toolId }.distinct()
        if (distinctTools.isEmpty()) return FastIntentMatchResult.NoMatch
        if (distinctTools.size > 1) {
            // CR-010/CR-013：确定性匹配产生冲突，不能唯一确定 Tool（IVAI-ROUTE-003）；
            // priority 只用于可兼容规则比较，不得静默决胜跨 canonical 冲突。
            return FastIntentMatchResult.Ambiguous(
                matches.map { ToolCandidateRef(it.tool.toolId, it.rule.ruleId, confidence(it.rule, it.slots)) },
                matchedToolIds = distinctTools.toSet(),
                matchedRuleIds = matches.map { it.rule.ruleId },
                deterministicCandidateCount = matches.size
            )
        }

        // 同一 canonical Tool 命中多条规则（如“主驾升温”命中通用 up 规则 + 表达规则）：
        // 选取优先级最高、槽位最完整、缺参最少的规则。
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
                matchedToolIds = setOf(only.tool.toolId),
                matchedRuleIds = matches.map { it.rule.ruleId },
                argumentSources = only.sources
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

    /**
     * CR-013：意图类型冲突防护。exactPhrases 是确定性词法触发器，命中后仍必须
     * 与对象/动作/OperationType 语义一致：
     *  - 动作类规则（CONTROL/CONFIGURE/PLAYBACK）命中文档包含导航/页面词 →
     *    NAVIGATE_UI 意图冲突（“打开空调设置页面”不得命中空调电源控制）；
     *  - CONTROL 规则命中查询词（吗/多少/查询/状态…）→ 查询意图冲突；
     *  - QUERY 规则命中执行/设置词（打开/关闭/设置…）→ 控制意图冲突。
     */
    private fun intentTypeConflict(rule: DeterministicIntentRule, tool: ToolDefinition, input: NormalizedInput): Boolean {
        val text = input.normalized
        val ops = tool.supportedOperations
        val isAction = ops.any { it in ACTION_OPERATION_TYPES }
        val isQuery = ops.contains(net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.QUERY)
        when {
            isAction && NAVIGATION_MARKERS.any { text.contains(it) } -> return true
            ops.contains(net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.CONTROL) &&
                QUERY_MARKERS.any { text.contains(it) } -> return true
            isQuery && ACTION_MARKERS.any { text.contains(it) } -> return true
        }
        return false
    }

    /**
     * CR-013 参数合并：显式槽位（用户文本）> 规则预置（默认值）。高优先级来源
     * 可覆盖低优先级默认值；但显式槽位与预置同时表达用户语义且值冲突时返回
     * 矛盾状态（IVAI-ROUTE-005），不得静默决胜。Schema 默认值与 Alias 映射
     * 由 L1 补槽链路处理，L0 不引入不可见默认。
     */
    private fun mergeArguments(rule: DeterministicIntentRule, input: NormalizedInput): ArgumentMerge {
        val merged = LinkedHashMap<String, Any?>()
        val sources = LinkedHashMap<String, String>()
        for ((key, value) in rule.presetArguments) {
            merged[key] = value
            sources[key] = SOURCE_RULE_PRESET
        }
        val slots = extractSlots(rule, input)
        for ((key, value) in slots) {
            val existing = merged[key]
            if (existing != null && existing != value) {
                // 两个来源都代表用户明确语义且值冲突 → IVAI-ROUTE-005。
                return ArgumentMerge(
                    arguments = emptyMap(),
                    conflict = key,
                    sources = sources + (key to SOURCE_EXPLICIT_SLOT)
                )
            }
            merged[key] = value
            sources[key] = SOURCE_EXPLICIT_SLOT
        }
        return ArgumentMerge(merged, null, sources)
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
        // CR-013：TIME 槽位需自然语言时间解析，L0 不做抽取（可选槽位允许缺失）。
        SlotType.TIME -> null
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
        val missing: List<String>,
        val sources: Map<String, String>
    )

    private data class ArgumentMerge(
        val arguments: Map<String, Any?>,
        val conflict: String? = null,
        val sources: Map<String, String> = emptyMap()
    )

    private companion object {
        const val SOURCE_EXPLICIT_SLOT = "explicit_slot"
        const val SOURCE_RULE_PRESET = "rule_preset"

        val ACTION_OPERATION_TYPES = setOf(
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.CONTROL,
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.CONFIGURE,
            net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType.PLAYBACK
        )

        /** NAVIGATE_UI 意图标记：“打开空调设置页面”不得命中空调电源控制。 */
        val NAVIGATION_MARKERS = listOf("设置页面", "配置页面", "页面", "界面", "面板", "菜单", "选项卡")

        /** 查询意图标记：CONTROL 规则命中查询词 → 冲突（含“为什么/怎么”知识问题）。 */
        val QUERY_MARKERS = listOf("吗", "多少", "查询", "怎么样", "是否", "状态", "有没有", "为什么", "怎么")

        /** 执行/设置意图标记：QUERY 规则命中执行词 → 冲突（“打开空调状态”属查询而非开电源）。 */
        val ACTION_MARKERS = listOf("打开", "关闭", "开启", "关掉", "设置", "调节", "调整")
    }
}
