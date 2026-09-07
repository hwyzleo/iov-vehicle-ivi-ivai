package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * L0 治理目录 JSON DTO（IVI-IVAI-DSN-CR-013 开发规范源：IVAI Tool Catalog v1）。
 *
 * 只承载 160 个 Tool 的 L0 治理字段（资格、原因、规则、正负例、冲突集、规则版本、
 * 评审状态）；Tool 的 Domain / Pack / Schema / Policy 等基础字段仍以
 * [ToolCatalogV1] 为准，由 [DeterministicRuleCompiler] 构建期关联。
 *
 * 规则使用结构化 JSON 保存（不压缩为不可校验的自然语言）；slotPatterns 的
 * [aliases] 引用受控别名词表名称（vehicle_position_v1 等），编译时展开为运行时
 * [net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.SlotPattern]。
 */
@Serializable
data class L0GovernanceCatalogSpec(
    val ruleVersion: String,
    val tools: List<L0ToolGovernanceSpec>
)

/** 单个 Tool 的 L0 治理条目。 */
@Serializable
data class L0ToolGovernanceSpec(
    val toolId: String,
    val support: String,
    val supportReason: String,
    val rules: List<L0RuleSpec> = emptyList(),
    val positiveExamples: List<String> = emptyList(),
    val negativeExamples: List<String> = emptyList(),
    val conflictToolIds: List<String> = emptyList(),
    val reviewStatus: String
)

/** 单条 L0 确定性规则（与 Catalog 结构化 JSON 一一对应）。 */
@Serializable
data class L0RuleSpec(
    val ruleId: String,
    val exactPhrases: List<String> = emptyList(),
    val slotPatterns: List<L0SlotPatternSpec> = emptyList(),
    /** JSON 标量值（boolean / number / string），编译时转为 Kotlin 基础类型。 */
    val presetArguments: Map<String, JsonElement> = emptyMap(),
    val negativePatterns: List<String> = emptyList(),
    val priority: Int = 0,
    val synonymPatterns: List<String> = emptyList()
)

/**
 * 槽位模板（Catalog JSON 表示）：[slot] 为 [SlotTypeName]（POSITION/TEMPERATURE/
 * STEP/NUMERIC/TIME），[argument] 为最终候选输出的 canonical 参数名（使用 Tool
 * Schema 的 canonical 名称），[aliases] 为受控别名词表名称（可为空）。
 */
@Serializable
data class L0SlotPatternSpec(
    val slot: String,
    val argument: String,
    val required: Boolean = false,
    val aliases: String? = null
)

/** Catalog 槽位类型名（与 SlotType 对应；字符串枚举便于 JSON 直读）。 */
object SlotTypeName {
    const val TEMPERATURE = "TEMPERATURE"
    const val POSITION = "POSITION"
    const val STEP = "STEP"
    const val NUMERIC = "NUMERIC"
    const val TIME = "TIME"
}

/**
 * L0 治理目录加载器：从 classpath 资源读取 JSON，反序列化为不可变数据。
 * 构建期（测试 / GovernanceWorkspace 初始化）只加载一次。
 */
object L0CatalogLoader {

    const val RESOURCE_PATH = "/l0/l0-governance-catalog.json"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun load(resourcePath: String = RESOURCE_PATH): L0GovernanceCatalogSpec {
        val stream = L0CatalogLoader::class.java.getResourceAsStream(resourcePath)
            ?: error("L0 治理目录资源缺失: $resourcePath")
        return stream.bufferedReader().use { json.decodeFromString(L0GovernanceCatalogSpec.serializer(), it.readText()) }
    }
}
