package net.hwyz.iov.vehicle.ivi.ivai.agent.router.deterministic

/**
 * L0 确定性降级原因（IVI-IVAI-DSN-CR-016）。
 *
 * 当 L0 已命中规则短语但无法形成合法唯一 Candidate 时，返回类型化降级原因，
 * 供 Router 决定进入 L1、追问或拒绝，并记录 deterministicFallbackReason 可观测性。
 */
enum class DeterministicFallbackReason {
    /** 数值/位置槽位无法按当前 Tool Schema 解析（IVAI-L0-SLOT-001）。 */
    SLOT_UNPARSEABLE,

    /** 必填槽位缺失（IVAI-L0-SLOT-002，进入缺参追问/L1）。 */
    REQUIRED_SLOT_MISSING,

    /** 参数越界（范围校验失败，IVAI-L0-SLOT-002）。 */
    ARGUMENT_OUT_OF_RANGE,

    /** 同为用户显式语义且值冲突（IVAI-ROUTE-005，不以优先级静默覆盖）。 */
    ARGUMENT_CONFLICT,

    /** 跨 Tool/Workflow 存在多个不可等价候选（IVAI-ROUTE-003）。 */
    MULTIPLE_CANDIDATES,

    /** 治理 Profile 未批准 / 词表未闭合 / 版本不一致（IVAI-GOV / IVAI-PARAM-002）。 */
    GOVERNANCE_NOT_ELIGIBLE,

    /** Policy 要求确认，不得跳过 Retrieval/LLM。 */
    POLICY_REQUIRES_CONFIRMATION
}
