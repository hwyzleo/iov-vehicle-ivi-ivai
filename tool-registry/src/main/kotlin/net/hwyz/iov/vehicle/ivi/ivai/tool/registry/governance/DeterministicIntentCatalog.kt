package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentProfile
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicIntentRule
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicSupport
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.L0ReviewStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotPattern
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Deterministic Rule Compiler（IVI-IVAI-DSN-CR-013）。
 *
 * 把 IVAI Tool Catalog v1 的 L0 治理条目（[L0ToolGovernanceSpec]）编译为运行时
 * [DeterministicIntentProfile]：翻译槽位模板（Catalog slot/argument/aliases →
 * 运行时 SlotPattern + 受控别名词表）、预置参数（JSON 标量 → Kotlin 基础类型）、
 * 冲突集与正负例。编译前必须通过 [L0QualificationValidator]。
 */
object DeterministicRuleCompiler {

    /** 编译单个条目；前提是该条目通过资格校验。 */
    fun compile(entry: L0ToolGovernanceSpec, ruleVersion: String): DeterministicIntentProfile {
        val support = DeterministicSupport.fromName(entry.support)
        val rules = if (support == DeterministicSupport.SUPPORTED) {
            entry.rules.map { compileRule(entry.toolId, it) }
        } else {
            // NOT_SUPPORTED / NEEDS_REVIEW 不生成生产规则。
            emptyList()
        }
        return DeterministicIntentProfile(
            toolId = entry.toolId,
            support = support,
            supportReason = entry.supportReason,
            rules = rules,
            positiveExamples = entry.positiveExamples,
            negativeExamples = entry.negativeExamples,
            conflictToolIds = entry.conflictToolIds.toSet(),
            ruleVersion = ruleVersion,
            reviewStatus = L0ReviewStatus.fromName(entry.reviewStatus)
        )
    }

    private fun compileRule(toolId: String, spec: L0RuleSpec): DeterministicIntentRule =
        DeterministicIntentRule(
            ruleId = spec.ruleId,
            toolId = toolId,
            exactPhrases = spec.exactPhrases,
            synonymPatterns = spec.synonymPatterns,
            slotPatterns = spec.slotPatterns.map { compileSlot(it) },
            negativePatterns = spec.negativePatterns,
            presetArguments = spec.presetArguments.mapValues { (_, v) -> scalarValue(v) },
            priority = spec.priority
        )

    /** Catalog 槽位 → 运行时 SlotPattern（canonical 参数名 + 受控别名词表展开）。 */
    private fun compileSlot(spec: L0SlotPatternSpec): SlotPattern = SlotPattern(
        name = spec.argument,
        type = slotTypeOf(spec.slot),
        required = spec.required,
        aliases = spec.aliases?.let { L0AliasVocabularies.resolve(it) } ?: emptyMap()
    )

    private fun slotTypeOf(slot: String): SlotType = when (slot) {
        SlotTypeName.TEMPERATURE -> SlotType.TEMPERATURE
        SlotTypeName.POSITION -> SlotType.POSITION
        SlotTypeName.STEP -> SlotType.STEP
        SlotTypeName.NUMERIC -> SlotType.NUMERIC
        SlotTypeName.TIME -> SlotType.TIME
        else -> throw IllegalArgumentException("未知槽位类型: $slot")
    }

    private fun scalarValue(value: JsonElement): Any? {
        val primitive = value as? JsonPrimitive ?: return null
        val content = primitive.contentOrNull ?: return null
        return when {
            primitive.isString -> content
            content == "true" -> true
            content == "false" -> false
            content.toLongOrNull() != null -> content.toLong()
            content.toDoubleOrNull() != null -> content.toDouble()
            else -> content
        }
    }
}

/**
 * 不可变 DeterministicIntentCatalog（IVI-IVAI-DSN-CR-013）。
 *
 * 运行时只加载编译产物。Catalog、编译产物、测试 Suite 和 Manifest 必须记录相同
 * [ruleVersion] 与 [contentHash]；不一致时不得启用 L0（IVAI-GOV-005）。
 * 构建期从 [ToolCatalogV1] 与 L0 治理目录生成一次，之后不可变。
 */
class DeterministicIntentCatalog private constructor(
    val ruleVersion: String,
    val profiles: Map<String, DeterministicIntentProfile>,
    val contentHash: String
) {

    /** 生产可用的确定性候选（SUPPORTED + 非 DEPRECATED + 有规则）。 */
    val productionToolIds: Set<String> by lazy {
        profiles.values.filter { it.productionEnabled }.map { it.toolId }.toSet()
    }

    fun profileFor(toolId: String): DeterministicIntentProfile? = profiles[toolId]

    /** 运行时规则集（仅生产启用 Profile 的规则）。 */
    fun rulesFor(toolId: String): List<DeterministicIntentRule> =
        profileFor(toolId)?.takeIf { it.productionEnabled }?.rules ?: emptyList()

    /** 稳定性：内容序列化 + 稳定 Hash（与 GovernanceManifest 的 L0 段一致）。
     * 覆盖规则内容、正负例、冲突集与评审状态，保证任何内容变化都反映到 Hash。 */
    fun stableJson(): String {
        val sb = StringBuilder()
        sb.append(ruleVersion).append('\n')
        profiles.keys.sorted().forEach { id ->
            val p = profiles[id]!!
            sb.append(id).append('|').append(p.support.name).append('|')
            sb.append(p.supportReason).append('|').append(p.reviewStatus.name).append('|')
            sb.append(p.positiveExamples.sorted().joinToString(",")).append('|')
            sb.append(p.negativeExamples.sorted().joinToString(",")).append('|')
            sb.append(p.conflictToolIds.sorted().joinToString(",")).append('|')
            sb.append(p.rules.joinToString(";") { r ->
                "${r.ruleId}:${r.exactPhrases.sorted().joinToString(",")}:" +
                    r.slotPatterns.joinToString(",") { s ->
                        "${s.name}=${s.type.name}(${s.required})" +
                            s.aliases.keys.sorted().joinToString("/")
                    } + ":${r.negativePatterns.sorted().joinToString(",")}:${r.priority}"
            }).append('\n')
        }
        return sb.toString()
    }

    companion object {
        /** 从治理目录构建并立即校验（构建期门禁，失败即抛异常）。 */
        fun build(
            spec: L0GovernanceCatalogSpec = L0CatalogLoader.load(),
            catalogTools: List<ToolGovernanceSpec> = ToolCatalogV1.ALL
        ): DeterministicIntentCatalog {
            val validation = L0QualificationValidator(spec.tools, catalogTools, spec.ruleVersion).validate()
            if (!validation.passed) {
                throw L0CatalogBuildException(
                    "L0 资格校验失败（GOV-005）: ${validation.issues.take(10).joinToString("; ") { it.message }}",
                    validation.issues
                )
            }
            val profiles = spec.tools.associate { it.toolId to DeterministicRuleCompiler.compile(it, spec.ruleVersion) }
            val catalog = DeterministicIntentCatalog(
                ruleVersion = spec.ruleVersion,
                profiles = profiles,
                contentHash = ""
            )
            return catalog.copyWithHash()
        }

        /** 未启用 L0 的空目录（规则版本 v0）。 */
        val EMPTY = DeterministicIntentCatalog("v0", emptyMap(), "empty")
    }

    private fun copyWithHash(): DeterministicIntentCatalog {
        val hash = GovernanceManifestBuilder.sha256(stableJson())
        return DeterministicIntentCatalog(ruleVersion, profiles, hash)
    }
}

/** L0 目录构建失败（发布门禁 IVAI-GOV-005）。 */
class L0CatalogBuildException(
    message: String,
    val issues: List<L0QualificationIssue>
) : RuntimeException(message)
