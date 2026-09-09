package net.hwyz.iov.vehicle.ivi.ivai.agent.router.deterministic

/**
 * 参数来源（IVI-IVAI-DSN-CR-016）。设计文档枚举：
 * USER_EXPLICIT / ALIAS_MAPPING / RULE_PRESET / SCHEMA_DEFAULT / MODEL_OUTPUT。
 * 用于可观测性（argumentSources）与冲突判定（同为用户显式语义且值冲突 →
 * ARGUMENT_CONFLICT）。
 */
enum class ArgumentSource {
    /** 用户文本中显式表达的槽位。 */
    USER_EXPLICIT,

    /** 受控 Alias 词表映射（位置/枚举/数值档位）。 */
    ALIAS_MAPPING,

    /** 规则预置参数（presetArguments）。 */
    RULE_PRESET,

    /** Tool Schema 默认值。 */
    SCHEMA_DEFAULT,

    /** 模型输出参数。 */
    MODEL_OUTPUT
}

/**
 * 槽位提取结果（IVI-IVAI-DSN-CR-016）：单个参数槽位 + 来源 + 证据区间。
 * [evidenceRange] 为命中文本在归一化输入中的起止索引（可观测性，可为 null）。
 */
data class ExtractedArgument(
    val name: String,
    val rawValue: Any?,
    val source: ArgumentSource,
    val evidenceRange: IntRange? = null
)

/**
 * 槽位提取结果（IVI-IVAI-DSN-CR-016）。
 *
 *  - [arguments]：带来源的槽位候选（同一参数可能来自多个来源，由调用方按
 *    合并优先级处理：用户显式 > Alias 映射 > 规则预置 > Schema 默认值）；
 *  - [conflict]：同为用户显式语义且值冲突时返回该参数名（ARGUMENT_CONFLICT）；
 *  - [missing]：规则声明的必填槽位中未提取到的参数名（供缺参追问/降级）。
 */
data class SlotExtractionResult(
    val arguments: List<ExtractedArgument> = emptyList(),
    val conflict: String? = null,
    val missing: List<String> = emptyList(),
    /** CR-017: 命中但当前车型座舱拓扑不适用（IVAI-ALIAS-TOPOLOGY-001，禁止 L0 直达）。 */
    val topologyViolations: List<String> = emptyList()
) {
    companion object {
        val EMPTY = SlotExtractionResult()
    }
}
