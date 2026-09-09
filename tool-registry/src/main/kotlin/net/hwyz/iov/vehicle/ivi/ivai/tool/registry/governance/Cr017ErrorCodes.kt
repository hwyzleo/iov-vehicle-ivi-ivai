package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

/**
 * CR-017 新增错误码（位置 Alias / Tool RAG 治理）。
 *
 * 与 CR-016 的 IVAI-STATE-001 / IVAI-PARAM-001 / IVAI-CANDIDATE-001 等细分码
 * 并存；汇总码语义不变（Trace / 导出辅助字段记录细分码）。
 */
object Cr017ErrorCodes {

    /** 位置 Alias 存在多个合法 canonical 值（宽泛表达或一对多命中），进入 L1 消歧/追问。 */
    const val ALIAS_AMBIGUOUS = "IVAI-ALIAS-AMBIGUOUS-001"

    /** Alias 对应位置不适用于当前车型座舱拓扑，禁止转换为可执行参数。 */
    const val ALIAS_TOPOLOGY = "IVAI-ALIAS-TOPOLOGY-001"

    /** Tool Retrieval 文档缺少 Schema、Alias 或来源版本（构建校验失败）。 */
    const val RAG_DOC = "IVAI-RAG-DOC-001"

    /** Catalog/Alias 已变更但当前索引未刷新，不得宣称新增 Alias 已对 RAG 生效。 */
    const val RAG_INDEX = "IVAI-RAG-INDEX-001"

    /** 注入模型的候选上下文缺少合法枚举或候选边界（IVAI-RAG-CONTEXT-001）。 */
    const val RAG_CONTEXT = "IVAI-RAG-CONTEXT-001"
}
