package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.DefaultAliasLexicons
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotPattern
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotType
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolAvailabilityCheck
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.VersionRange
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema.CanonicalizationResult
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema.CanonicalizationSource
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema.DefaultParameterCanonicalizationService
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema.ParameterCanonicalizationService
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.deterministic.ArgumentSource
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.deterministic.DeterministicFallbackReason
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.deterministic.SchemaAwareSlotExtractor
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.deterministic.SlotExtractionResult
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.TemperatureIntentSemanticNormalizer
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr019ErrorCodes
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.TemperatureOperationSemantic

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
    private val scopeToolIds: Set<String>? = null,
    /** CR-016：Schema 感知槽位提取器（默认共享实例，注入同一 Alias Lexicon）。 */
    private val slotExtractor: SchemaAwareSlotExtractor =
        SchemaAwareSlotExtractor(registry, DefaultAliasLexicons.DEFAULT),
    /** CR-016：统一参数规范化服务（L0/L1/Validator/Scorer 共享同一实例）。 */
    private val canonicalizer: ParameterCanonicalizationService =
        DefaultParameterCanonicalizationService(registry, DefaultAliasLexicons.DEFAULT),
    /** CR-019：温度语义标准化器（相对/绝对/边界/越界/歧义判定 + adjust/set 边界）。 */
    private val temperatureNormalizer: TemperatureIntentSemanticNormalizer? =
        TemperatureIntentSemanticNormalizer()
) : FastIntentMatcher {

    override suspend fun match(input: NormalizedInput, context: AgentContext): FastIntentMatchResult {
        if (input.hasNegation) return FastIntentMatchResult.NoMatch
        if (input.hasMultiIntent) return FastIntentMatchResult.NoMatch

        // CR-019：温度语义边界（仅对温度相关请求干预，evidence 为空视为非温度请求）。
        val temperatureSemantic = temperatureNormalizer?.normalize(input.normalized, context.vehicleModel)
        if (temperatureSemantic != null && temperatureSemantic.evidence.isNotEmpty()) {
            when (temperatureSemantic.operation) {
                // 绝对目标越界 → 直接拒绝（IVAI-TEMP-RANGE-001），不进入 L1 执行。
                TemperatureOperationSemantic.OUT_OF_RANGE -> return FastIntentMatchResult.Rejected(
                    reasonCode = Cr019ErrorCodes.TEMP_RANGE,
                    semantic = temperatureSemantic.evidence.joinToString(",")
                )
                // 数值缺少单位 / 动作对象无法判断 → 追问（IVAI-TEMP-SEMANTIC-001）。
                TemperatureOperationSemantic.AMBIGUOUS -> return FastIntentMatchResult.NeedsDialogue(
                    reasonCode = Cr019ErrorCodes.TEMP_SEMANTIC,
                    semantic = temperatureSemantic.evidence.joinToString(",")
                )
                else -> {}
            }
        }

        val matches = mutableListOf<RuleMatch>()
        // CR-016：canonicalize 失败（越界/类型/缺参）的规则——跳过继续匹配其他规则，
        // 全部失败时作为类型化降级原因返回（IVAI-L0-SLOT-001/002）。
        val fallbacks = mutableListOf<FallbackMatch>()
        for (tool in registry.all()) {
            if (scopeToolIds != null && tool.toolId !in scopeToolIds) continue
            if (!ToolAvailabilityCheck.isAvailable(tool, context.vehicleModel, context.softwareVersion)) {
                continue
            }
            for (rule in tool.deterministicRules) {
                if (!versionAllowed(rule, context.softwareVersion)) continue
                // CR-019：温度操作语义与 Tool 边界一致性——相对增减不得命中
                // temperature.set，绝对/边界目标不得命中 temperature.adjust。
                if (temperatureSemantic != null && temperatureSemantic.evidence.isNotEmpty()) {
                    when (temperatureSemantic.operation) {
                        TemperatureOperationSemantic.RELATIVE_DELTA ->
                            if (tool.toolId == TEMPERATURE_SET_TOOL) continue
                        TemperatureOperationSemantic.ABSOLUTE_TARGET,
                        TemperatureOperationSemantic.BOUND_TARGET ->
                            if (tool.toolId == TEMPERATURE_ADJUST_TOOL) continue
                        else -> {}
                    }
                }
                if (!ruleMatches(rule, input)) continue
                // CR-013：短语命中 ≠ 可执行；导航/查询/配置意图冲突直接拦截。
                if (intentTypeConflict(rule, tool, input)) continue
                // CR-016：Schema 感知槽位提取（带来源）+ 统一参数规范化。
                val extraction = slotExtractor.extract(rule, tool.toolId, input.normalized, context.vehicleModel)
                // CR-017：位置命中但车型座舱拓扑不适用 → 不得 L0 直达（IVAI-ALIAS-TOPOLOGY-001），
                // 交由 L1 处理（Resolver 输出明确错误码）。
                if (extraction.topologyViolations.isNotEmpty()) {
                    return FastIntentMatchResult.NoMatch
                }
                // 同为用户显式语义且值冲突 → IVAI-ROUTE-005（不得静默决胜）。
                if (extraction.conflict != null) {
                    return FastIntentMatchResult.ArgumentConflict(
                        toolId = tool.toolId,
                        conflictingArgument = extraction.conflict,
                        sources = extraction.sourcesCompat(),
                        deterministicFallbackReason = DeterministicFallbackReason.ARGUMENT_CONFLICT.name,
                        matchedRuleIds = matches.map { it.rule.ruleId } + rule.ruleId
                    )
                }
                // CR-013：参数合并（显式槽位 > Alias 映射 > 规则预置），矛盾 → IVAI-ROUTE-005。
                val merge = mergeArguments(rule, extraction)
                if (merge.conflict != null) {
                    return FastIntentMatchResult.ArgumentConflict(
                        toolId = tool.toolId,
                        conflictingArgument = merge.conflict,
                        sources = merge.sources,
                        deterministicFallbackReason = DeterministicFallbackReason.ARGUMENT_CONFLICT.name,
                        matchedRuleIds = matches.map { it.rule.ruleId } + rule.ruleId
                    )
                }
                // 规则声明的必填槽位缺失 → 该规则缺参（IVAI-L0-SLOT-002）。
                val missing = rule.slotPatterns
                    .filter { it.required && merge.arguments[it.name] == null }
                    .map { it.name }
                if (missing.isNotEmpty()) {
                    matches += RuleMatch(tool, rule, merge.arguments, missing, merge.sources + extraction.sourcesCompat())
                    continue
                }
                // CR-016：统一 canonicalize（类型/枚举/范围/冲突校验）。
                // 必填语义以规则声明的必填槽位为准（Schema required 可能更宽，如
                // adjust 的 step 规则可选但 Schema 必填——不是缺参）。
                val ruleRequired = rule.slotPatterns.filter { it.required }.map { it.name }.toSet()
                val canonical = canonicalizer.canonicalize(
                    tool.toolId, merge.arguments, CanonicalizationSource.L0_RULE,
                    requiredOverride = ruleRequired
                )
                if (canonical is CanonicalizationResult.Failed) {
                    // 越界 / 类型非法：该规则无法形成合法 L0 候选，记录降级原因后继续
                    // 尝试其他规则（不得因一条越界规则中断同输入的正确规则匹配）。
                    fallbacks += FallbackMatch(
                        tool = tool,
                        rule = rule,
                        fallbackReason = if (canonical.missingArguments.isNotEmpty()) {
                            DeterministicFallbackReason.REQUIRED_SLOT_MISSING
                        } else {
                            DeterministicFallbackReason.ARGUMENT_OUT_OF_RANGE
                        },
                        message = canonical.message
                    )
                    continue
                }
                matches += RuleMatch(
                    tool, rule,
                    (canonical as CanonicalizationResult.Success).canonicalArguments,
                    missing, merge.sources + extraction.sourcesCompat()
                )
            }
        }

        val distinctTools = matches.map { it.tool.toolId }.distinct()
        if (distinctTools.isEmpty()) {
            // CR-016：无合法候选但有越界/缺参降级记录 → 类型化降级（禁止生成 L0 Candidate）。
            val first = fallbacks.firstOrNull()
            if (first != null) {
                return FastIntentMatchResult.MissingArguments(
                    toolId = first.tool.toolId,
                    missing = if (first.fallbackReason == DeterministicFallbackReason.REQUIRED_SLOT_MISSING) {
                        first.rule.slotPatterns.filter { it.required }.map { it.name }
                    } else {
                        emptyList()
                    },
                    deterministicFallbackReason = first.fallbackReason.name,
                    matchedRuleIds = fallbacks.map { it.rule.ruleId }
                )
            }
            return FastIntentMatchResult.NoMatch
        }
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
            FastIntentMatchResult.MissingArguments(
                toolId = only.tool.toolId,
                missing = only.missing,
                deterministicFallbackReason = DeterministicFallbackReason.REQUIRED_SLOT_MISSING.name,
                matchedRuleIds = matches.map { it.rule.ruleId }
            )
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
     * CR-013 + CR-016 参数合并：用户显式槽位 > Alias 映射 > 规则预置（默认值由
     * canonicalizer 的 Schema 默认值步骤处理）。高优先级来源可覆盖低优先级默认值；
     * 但显式槽位与预置同时表达用户语义且值冲突时返回矛盾状态（IVAI-ROUTE-005），
     * 不得静默决胜。
     */
    private fun mergeArguments(rule: DeterministicIntentRule, extraction: SlotExtractionResult): ArgumentMerge {
        val merged = LinkedHashMap<String, Any?>()
        val sources = LinkedHashMap<String, String>()
        for ((key, value) in rule.presetArguments) {
            merged[key] = value
            sources[key] = SOURCE_RULE_PRESET
        }
        // 提取结果按来源优先级排序：用户显式 > Alias 映射。
        val ordered = extraction.arguments.sortedBy { priorityOf(it.source) }
        for (arg in ordered) {
            val key = arg.name
            val value = arg.rawValue
            val existing = merged[key]
            if (existing != null && existing != value) {
                // 两个来源都代表用户明确语义且值冲突 → IVAI-ROUTE-005。
                return ArgumentMerge(
                    arguments = emptyMap(),
                    conflict = key,
                    sources = sources + (key to sourceCompat(arg.source))
                )
            }
            merged[key] = value
            sources[key] = sourceCompat(arg.source)
        }
        return ArgumentMerge(merged, null, sources)
    }

    private fun priorityOf(source: ArgumentSource): Int = when (source) {
        ArgumentSource.USER_EXPLICIT -> 0
        ArgumentSource.ALIAS_MAPPING -> 1
        ArgumentSource.RULE_PRESET -> 2
        ArgumentSource.SCHEMA_DEFAULT -> 3
        ArgumentSource.MODEL_OUTPUT -> 4
    }

    private fun sourceCompat(source: ArgumentSource): String = when (source) {
        ArgumentSource.USER_EXPLICIT -> SOURCE_EXPLICIT_SLOT
        ArgumentSource.ALIAS_MAPPING -> SOURCE_ALIAS_MAPPING
        ArgumentSource.RULE_PRESET -> SOURCE_RULE_PRESET
        ArgumentSource.SCHEMA_DEFAULT -> SOURCE_SCHEMA_DEFAULT
        ArgumentSource.MODEL_OUTPUT -> SOURCE_MODEL_OUTPUT
    }

    private fun SlotExtractionResult.sourcesCompat(): Map<String, String> =
        arguments.associate { it.name to sourceCompat(it.source) }

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

    /** CR-016: canonicalize 失败的规则（类型化降级原因，IVAI-L0-SLOT-001/002）。 */
    private data class FallbackMatch(
        val tool: ToolDefinition,
        val rule: DeterministicIntentRule,
        val fallbackReason: DeterministicFallbackReason,
        val message: String
    )

    private data class ArgumentMerge(
        val arguments: Map<String, Any?>,
        val conflict: String? = null,
        val sources: Map<String, String> = emptyMap()
    )

    private companion object {
        const val SOURCE_EXPLICIT_SLOT = "explicit_slot"
        const val SOURCE_ALIAS_MAPPING = "alias_mapping"
        const val SOURCE_RULE_PRESET = "rule_preset"
        const val SOURCE_SCHEMA_DEFAULT = "schema_default"
        const val SOURCE_MODEL_OUTPUT = "model_output"

        /** CR-019：温度 Tool canonical ID（adjust/set 语义边界）。 */
        const val TEMPERATURE_SET_TOOL = "climate.temperature.set"
        const val TEMPERATURE_ADJUST_TOOL = "climate.temperature.adjust"

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
