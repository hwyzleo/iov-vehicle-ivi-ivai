package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain

/**
 * 功能治理状态（IVI-IVAI-DSN-CR-008）。只有 [APPROVED] 且存在当前运行环境
 * 有效 Execution Binding 的 Tool/Workflow 才能进入运行时索引；[DRAFT] /
 * [NEEDS_REVIEW] 可进入治理工作区和离线评测集，但不得进入可执行候选集合。
 */
enum class GovernanceStatus {
    DRAFT,
    NEEDS_REVIEW,
    APPROVED,
    DEPRECATED,
    REJECTED
}

/**
 * Tool Alias 的来源类型（IVI-IVAI-DSN-CR-008 参数化 Tool 追溯）：
 *  - [LEGACY_TOOL_ID]：迁移期保留的旧 Tool ID（如 climate.temperature_increase）。
 *  - [FUNCTION_ID]：兼容 Function-ID（如 AC_Temperature_2）。
 *  - [ORIGINAL_FEATURE]：原始标准功能条目（原始记录键 / 原始二级功能）。
 *  - [EXPRESSION]：代表说法 / 表达（主驾升温 → position=driver,direction=up）。
 */
enum class AliasSourceType {
    LEGACY_TOOL_ID,
    FUNCTION_ID,
    ORIGINAL_FEATURE,
    EXPRESSION
}

/**
 * 执行 Binding 状态（IVI-IVAI-DSN-CR-008 治理数据模型）。运行时索引只允许
 * [BOUND]；[PENDING] / [NO_BINDING] 的 Tool/Workflow 被 IVAI-BINDING-001 /
 * IVAI-GOV-001 阻止进入可执行候选。
 */
enum class BindingStatus {
    BOUND,
    PENDING,
    NO_BINDING
}

/**
 * Tool Alias（IVI-IVAI-DSN-CR-008 参数化 Tool / 归并追溯）。
 *
 * 参数化 Tool 表达稳定业务动作；原始功能、Function-ID 与旧 Tool ID 通过 Alias
 * 保留可追溯映射。[mappedArguments] 在 L1 补槽 / 归并时预置参数
 * （如「主驾升温」→ position=driver, direction=up）。原始字段不得覆盖；
 * 正式映射使用建议项，但必须保留原始项与治理原因、版本。
 */
data class ToolAlias(
    val aliasId: String,
    val sourceType: AliasSourceType,
    val sourceValue: String,
    val originalDomain: String? = null,
    val originalFunctionId: String? = null,
    val originalRecordKey: String? = null,
    val mappedArguments: Map<String, Any?> = emptyMap(),
    val status: GovernanceStatus = GovernanceStatus.APPROVED
)
