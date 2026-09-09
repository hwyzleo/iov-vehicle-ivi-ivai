package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schema

/**
 * 参数规范化结果的候选来源语义标签（IVI-IVAI-DSN-CR-016）。
 *
 * 与 agent-core 的 CandidateSource 一一对应；tool-registry 不依赖 agent-core，
 * 故在共享层定义等价标签，由装配方在边界处映射。
 */
enum class CanonicalizationSource {
    /** L0 确定性规则候选。 */
    L0_RULE,

    /** L1 本地模型候选。 */
    L1_LOCAL_LLM,

    /** L3 云端候选（预留）。 */
    L3_CLOUD_AI,

    /** ToolValidator / 执行链规范化（非候选产生路径）。 */
    TOOL_VALIDATOR,

    /** TestScorer / EvaluationSnapshot 评分侧规范化。 */
    TEST_SCORER
}

/** 规范化结果。 */
sealed interface CanonicalizationResult {

    /**
     * 规范化成功。[canonicalArguments] 只保存 canonical 参数（稳定 JSON 序列化）；
     * [argumentSources] 为参数 → 来源（USER_EXPLICIT / ALIAS_MAPPING /
     * RULE_PRESET / SCHEMA_DEFAULT / MODEL_OUTPUT，字符串形式）。
     */
    data class Success(
        val canonicalArguments: Map<String, Any?>,
        val argumentSources: Map<String, String> = emptyMap()
    ) : CanonicalizationResult

    /**
     * 规范化失败（IVAI-PARAM-001 / IVAI-PARAM-002）。[missingArguments] 为必填
     * 缺失参数名（供 L0 缺参降级与追问使用）。
     */
    data class Failed(
        val errorCode: String,
        val message: String,
        val missingArguments: List<String> = emptyList()
    ) : CanonicalizationResult
}

/**
 * Schema 感知的统一参数规范化服务（IVI-IVAI-DSN-CR-016）。
 *
 * 处理顺序（设计结论）：
 *   参数名 Alias → 类型转换 → 字段级枚举 Alias → 单位转换
 *   → 范围校验 → 必填校验 → 冲突校验 → 稳定 JSON 序列化
 *
 * 约束：
 *  - 枚举只能依据当前 Tool Schema 和字段级 Alias 表转换；
 *  - 可将 ALL / all / 全部 / 所有 / 整车 映射为 Schema 批准的同一 canonical 值，
 *    但不得对自由文本统一 lowercase；
 *  - L0、L1、ToolValidator、EvaluationSnapshot 和 TestScorer 注入同一服务实例
 *    与同一 Alias/Schema 版本（版本不一致 → IVAI-PARAM-002）。
 */
interface ParameterCanonicalizationService {
    /**
     * 规范化参数。
     *
     * @param requiredOverride 必填参数集合覆盖：为空时使用 Tool Schema 的 required；
     *   非空时以此为准（L0 规则声明的必填槽位可能比 Schema 更精确，例如可选 step）。
     */
    fun canonicalize(
        targetId: String,
        arguments: Map<String, Any?>,
        source: CanonicalizationSource,
        requiredOverride: Set<String>? = null
    ): CanonicalizationResult
}
