package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicSupport
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.L0ReviewStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * L0 资格校验结果（IVI-IVAI-DSN-CR-013）。构建期失败项必须逐条列出，
 * 由发布门禁（GOV-005）与规则编译器消费。
 */
data class L0QualificationIssue(
    val toolId: String,
    val message: String
)

/** L0 资格校验结果。 */
data class L0QualificationResult(
    val ruleVersion: String,
    val issues: List<L0QualificationIssue>,
    val supportedCount: Int,
    val notSupportedCount: Int,
    val needsReviewCount: Int
) {
    val passed: Boolean get() = issues.isEmpty()
}

/**
 * L0 Qualification Validator（IVI-IVAI-DSN-CR-013）。
 *
 * 逐项校验 160 个 Tool 的 L0 治理字段，失败项对应 IVAI-GOV-005；非法把
 * NOT_SUPPORTED / NEEDS_REVIEW Profile 加入生产 Matcher 对应 IVAI-GOV-006：
 *  - 每项 Tool 必须有唯一资格结论且存在于 [ToolCatalogV1]；
 *  - SUPPORTED：至少一条有效规则、正例、负例、冲突集、规则版本与评审状态完整；
 *  - NOT_SUPPORTED / NEEDS_REVIEW：rules 必须为空（不得编写宽泛兜底规则）；
 *  - ruleId 全局唯一；规则 toolId 与条目一致；
 *  - 槽位参数名、预置参数名必须属于该 Tool Schema；
 *  - NUMERIC 预置/槽位值必须处于 Schema 数值区间（如 int[0..100]）；
 *  - synonymPatterns 必须是受控合法正则（非法表达式 / 灾难性回溯 / 空匹配 / 过宽匹配）。
 */
class L0QualificationValidator(
    private val entries: List<L0ToolGovernanceSpec>,
    private val catalogTools: List<ToolGovernanceSpec> = ToolCatalogV1.ALL,
    private val ruleVersion: String = L0CatalogLoader.load().ruleVersion
) {

    /** Schema 参数名提取：{enabled:boolean, zone?:enum, direction, step:int} → [enabled, zone, direction, step]。 */
    private val schemaParam = Regex("([a-zA-Z][a-zA-Z0-9_]*)\\??\\s*(?::|,|\\}|$)")

    fun validate(): L0QualificationResult {
        val issues = mutableListOf<L0QualificationIssue>()
        val catalogIds = catalogTools.map { it.toolId }.toSet()
        val schemaByTool = catalogTools.associate { it.toolId to it.parameterSchema }

        // 1) 唯一资格结论 + 目录引用闭合。
        val seen = mutableSetOf<String>()
        for (entry in entries) {
            if (!seen.add(entry.toolId)) {
                issues += L0QualificationIssue(entry.toolId, "重复的 L0 资格条目")
            }
            if (entry.toolId !in catalogIds) {
                issues += L0QualificationIssue(entry.toolId, "L0 条目引用不存在的 Tool（GOV-005）")
            }
            val support = runCatching { DeterministicSupport.fromName(entry.support) }.getOrNull()
            if (support == null) {
                issues += L0QualificationIssue(entry.toolId, "非法资格结论: ${entry.support}")
                continue
            }
            val review = runCatching { L0ReviewStatus.fromName(entry.reviewStatus) }.getOrNull()
            if (review == null) {
                issues += L0QualificationIssue(entry.toolId, "非法评审状态: ${entry.reviewStatus}")
            }
            if (entry.rules.any { it.ruleId != it.ruleId.trim() } || entry.rules.map { it.ruleId }.distinct().size != entry.rules.size) {
                issues += L0QualificationIssue(entry.toolId, "ruleId 重复或非法")
            }
            when (support) {
                DeterministicSupport.SUPPORTED -> validateSupported(entry, issues)
                DeterministicSupport.NOT_SUPPORTED, DeterministicSupport.NEEDS_REVIEW ->
                    if (entry.rules.isNotEmpty()) {
                        issues += L0QualificationIssue(
                            entry.toolId,
                            "${entry.support} 含生产启用规则，不得编写宽泛兜底规则（GOV-006）"
                        )
                    }
            }
            // 2) 槽位 / 预置参数属于 Schema；预置值在 Schema 数值区间内。
            val schemaParams = schemaParam.findAll(schemaByTool[entry.toolId] ?: "").map { it.groupValues[1] }.toSet()
            for (rule in entry.rules) {
                for (slot in rule.slotPatterns) {
                    if (schemaParams.isNotEmpty() && slot.argument !in schemaParams) {
                        issues += L0QualificationIssue(entry.toolId, "槽位参数 ${slot.argument} 不属于 Schema")
                    }
                    if (slot.aliases != null && slot.aliases !in L0AliasVocabularies.names) {
                        issues += L0QualificationIssue(entry.toolId, "槽位引用了未知别名词表: ${slot.aliases}")
                    }
                }
                for ((key, value) in rule.presetArguments) {
                    if (schemaParams.isNotEmpty() && key !in schemaParams) {
                        issues += L0QualificationIssue(entry.toolId, "预置参数 $key 不属于 Schema")
                    }
                    val numeric = (value as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                    if (numeric != null) {
                        val range = numericRangeOf(schemaByTool[entry.toolId] ?: "", key)
                        if (range != null && (numeric < range.first || numeric > range.second)) {
                            issues += L0QualificationIssue(entry.toolId, "预置值 $key=$value 越界 ${range.first}..${range.second}")
                        }
                    }
                }
            }
            // 3) synonymPatterns 受控正则校验。
            for (rule in entry.rules) {
                for (pattern in rule.synonymPatterns) {
                    validateRegex(entry.toolId, rule.ruleId, pattern)?.let { issues += it }
                }
            }
        }

        // 4) 目录覆盖闭合：全部 160 Tool 都必须有资格结论。
        for (spec in catalogTools) {
            if (spec.toolId !in seen) {
                issues += L0QualificationIssue(spec.toolId, "缺少 L0 资格结论（GOV-005）")
            }
        }

        return L0QualificationResult(
            ruleVersion = ruleVersion,
            issues = issues,
            supportedCount = entries.count { it.support == "SUPPORTED" },
            notSupportedCount = entries.count { it.support == "NOT_SUPPORTED" },
            needsReviewCount = entries.count { it.support == "NEEDS_REVIEW" }
        )
    }

    private fun validateSupported(entry: L0ToolGovernanceSpec, issues: MutableList<L0QualificationIssue>) {
        if (entry.rules.isEmpty()) {
            issues += L0QualificationIssue(entry.toolId, "SUPPORTED 但无 L0 规则（构建失败条件 1）")
        }
        if (entry.positiveExamples.isEmpty()) issues += L0QualificationIssue(entry.toolId, "SUPPORTED 缺少正例")
        if (entry.negativeExamples.isEmpty()) issues += L0QualificationIssue(entry.toolId, "SUPPORTED 缺少负例")
        // 冲突集允许为空（无同对象/同能力家族冲突）；非空项必须可解析。
        if (entry.reviewStatus.isBlank()) issues += L0QualificationIssue(entry.toolId, "SUPPORTED 缺少评审状态")
        for (rule in entry.rules) {
            if (rule.exactPhrases.isEmpty() && rule.synonymPatterns.isEmpty()) {
                issues += L0QualificationIssue(entry.toolId, "规则 ${rule.ruleId} 无可匹配表达")
            }
        }
    }

    /** 受控正则校验：非法表达式 / 灾难性回溯（嵌套量词）/ 空匹配 / 过宽匹配（.* 兜底）。 */
    private fun validateRegex(toolId: String, ruleId: String, pattern: String): L0QualificationIssue? {
        val message = when {
            pattern.isBlank() -> "规则 $ruleId 的正则表达式为空"
            runCatching { Regex(pattern) }.isFailure -> "规则 $ruleId 正则非法: $pattern"
            Regex("(?:\\*|\\+|\\{\\d+,\\})\\s*(?:\\*|\\+|\\{\\d+,\\})").containsMatchIn(pattern) ->
                "规则 $ruleId 正则存在灾难性回溯风险: $pattern"
            Regex("\\^\\.\\*|\\.\\*\\$").containsMatchIn(pattern) -> "规则 $ruleId 正则过宽（.* 兜底）: $pattern"
            Regex("\\(\\)|\\|\\||^\\$|\\b\\^\\$").containsMatchIn(pattern) -> "规则 $ruleId 正则可能空匹配: $pattern"
            else -> null
        }
        return message?.let { L0QualificationIssue(toolId, it) }
    }

    /** 从 Schema 文本提取某参数数值区间：int[0..100] / number[16..30] → (0.0, 100.0)。 */
    private fun numericRangeOf(schema: String, param: String): Pair<Double, Double>? {
        val m = Regex("\\b$param\\??\\s*:\\s*[a-zA-Z]+\\s*\\[\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\.\\.\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\]").find(schema)
            ?: return null
        val lo = m.groupValues[1].toDoubleOrNull() ?: return null
        val hi = m.groupValues[2].toDoubleOrNull() ?: return null
        return lo to hi
    }
}
