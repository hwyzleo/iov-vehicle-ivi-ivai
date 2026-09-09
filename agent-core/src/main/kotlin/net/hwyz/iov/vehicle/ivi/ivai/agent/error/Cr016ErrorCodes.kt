package net.hwyz.iov.vehicle.ivi.ivai.agent.error

/**
 * CR-016 细分错误码（IVI-IVAI-DSN-CR-016 错误码表）。
 *
 * 原有 IVAI-MODEL-001/002、IVAI-TOOL-001/002 继续作为兼容汇总码；本组细分码用于
 * 内部 Trace 与新增导出辅助字段（failureStage / fineGrainedErrorCode），帮助
 * 定位失败发生在哪一层。
 */
object Cr016ErrorCodes {
    /** 执行状态、层级、LLM 调用、计时或终态组合违反不变量。 */
    const val STATE_INVARIANT = "IVAI-STATE-001"

    /** L0 数值或位置槽位无法按当前 Schema 解析。 */
    const val L0_SLOT_PARSE = "IVAI-L0-SLOT-001"

    /** 必填槽位缺失、冲突或越界，禁止生成 L0 Candidate。 */
    const val L0_SLOT_INCOMPLETE = "IVAI-L0-SLOT-002"

    /** 参数名、类型、枚举或单位无法 canonicalize。 */
    const val PARAM_CANONICALIZE = "IVAI-PARAM-001"

    /** Alias/Schema/Canonicalizer 版本不一致。 */
    const val PARAM_VERSION_MISMATCH = "IVAI-PARAM-002"

    /** 模型响应无法安全修复或修复后仍不合法。 */
    const val MODEL_REPAIR_FAILED = "IVAI-MODEL-REPAIR-001"

    /** 请求排队超时。 */
    const val MODEL_TIMEOUT_QUEUE = "IVAI-MODEL-TIMEOUT-001"

    /** 首个可消费输出超时。 */
    const val MODEL_TIMEOUT_FIRST_OUTPUT = "IVAI-MODEL-TIMEOUT-002"

    /** 流式响应连续无输出超时。 */
    const val MODEL_TIMEOUT_IDLE = "IVAI-MODEL-TIMEOUT-003"

    /** 模型总生成超时。 */
    const val MODEL_TIMEOUT_TOTAL = "IVAI-MODEL-TIMEOUT-004"

    /** Provider 取消或资源清理未在限定时间完成。 */
    const val MODEL_CLEANUP = "IVAI-MODEL-CLEANUP-001"

    /** L1 候选为空或无法形成受控 CandidateSet。 */
    const val CANDIDATE_SET_EMPTY = "IVAI-CANDIDATE-001"
}
