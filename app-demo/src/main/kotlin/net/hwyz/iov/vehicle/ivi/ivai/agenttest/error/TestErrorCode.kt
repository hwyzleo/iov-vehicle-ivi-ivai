package net.hwyz.iov.vehicle.ivi.ivai.agenttest.error

/**
 * CR-012 测试错误码（IVI-IVAI-DSN-CR-012 错误码表）。
 */
object TestErrorCode {
    /** 测试 Suite 或 JSON Schema 非法。 */
    const val SUITE_INVALID = "IVAI-TEST-001"

    /** caseId 重复或测试用例字段缺失。 */
    const val CASE_INVALID = "IVAI-TEST-002"

    /** 当前构建或 Binding 环境不允许批量测试。 */
    const val ENVIRONMENT_FORBIDDEN = "IVAI-TEST-003"

    /** 测试用例提交失败或 Agent 服务不可用。 */
    const val SUBMIT_FAILED = "IVAI-TEST-004"

    /** 单条用例等待结果超时。 */
    const val CASE_TIMEOUT = "IVAI-TEST-005"

    /** 结构化实际结果缺失或无法关联 requestId。 */
    const val RESULT_MISSING = "IVAI-TEST-006"

    /** 服务端活动 Turn 持续未释放（串行语义被阻塞，等待+强制取消后仍不可用）。 */
    const val SERVICE_BUSY = "IVAI-TEST-009"

    /** 参数规范化或 Schema 解析失败。 */
    const val CANONICALIZATION_FAILED = "IVAI-TEST-007"

    /** 批次重复启动或状态转换非法。 */
    const val BATCH_STATE_INVALID = "IVAI-TEST-008"

    // ---- IVI-IVAI-DSN-CR-014：批次结果导出（IVAI-REQ-138/139） ----

    /** 导出批次为空。 */
    const val EXPORT_EMPTY = "IVAI-TEST-010"

    /** 批次存在未进入终态的用例，禁止导出。 */
    const val EXPORT_NOT_TERMINAL = "IVAI-TEST-011"

    /** 批次存在重复 caseId，禁止导出。 */
    const val EXPORT_DUPLICATE_CASE_ID = "IVAI-TEST-012"

    /** 计时一致性校验失败（耗时非负 / 大小关系 / llmInvoked 语义）。 */
    const val EXPORT_INVALID_TIMING = "IVAI-TEST-013"

    /** 参数 JSON 序列化失败。 */
    const val EXPORT_SERIALIZE_FAILED = "IVAI-TEST-014"

    /** 导出过程异常或批次 ID 非法。 */
    const val EXPORT_INVALID = "IVAI-TEST-015"
}
