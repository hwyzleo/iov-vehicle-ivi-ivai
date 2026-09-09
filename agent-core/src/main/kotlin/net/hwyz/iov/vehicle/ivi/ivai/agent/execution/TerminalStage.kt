package net.hwyz.iov.vehicle.ivi.ivai.agent.execution

/**
 * 执行终止阶段（IVI-IVAI-DSN-CR-016）：所有非成功终态必须携带 [terminalStage]，
 * 用于定位失败发生在哪一层（Router / Retriever / Model / Parse / Schema /
 * Policy / Execution）。成功终态为 [SUCCESS]。
 *
 * 阶段顺序与总体链路一致：
 * 输入规范化 → 领域路由 → L0/L1 决策 → 检索 → 模型生命周期 → 解析/修复 →
 * 候选边界 → 策略/确认/幂等 → 执行器/Adapter → 快照投影。
 */
enum class TerminalStage {
    /** 输入规范化 / 安全预检 / 领域路由 / 能力包选择。 */
    ROUTING,

    /** L0 确定性匹配（SchemaAwareSlotExtractor / 参数合并）。 */
    L0_MATCHING,

    /** L1/L2 候选检索（Retriever / RAG）。 */
    RETRIEVAL,

    /** 模型请求生命周期（排队 / 首字 / 流式 / 总生成）。 */
    MODEL_REQUEST,

    /** 模型响应解析、SafeJsonRepair 与结构化校验。 */
    MODEL_PARSE,

    /** 候选边界（CandidateSet 成员校验 / Schema / 范围 / 冲突 / 冻结）。 */
    CANDIDATE_BOUNDARY,

    /** 策略引擎（风险 / 预条件 / 授权）。 */
    POLICY,

    /** 用户确认等待。 */
    CONFIRMATION,

    /** 幂等检查。 */
    IDEMPOTENCY,

    /** 工具执行器 / Adapter。 */
    EXECUTION,

    /** 快照投影与终态封口。 */
    SNAPSHOT,

    /** 成功终态（无失败阶段）。 */
    SUCCESS
}
