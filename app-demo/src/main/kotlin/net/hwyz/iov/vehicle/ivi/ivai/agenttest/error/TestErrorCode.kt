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

    /** 参数规范化或 Schema 解析失败。 */
    const val CANONICALIZATION_FAILED = "IVAI-TEST-007"

    /** 批次重复启动或状态转换非法。 */
    const val BATCH_STATE_INVALID = "IVAI-TEST-008"
}
