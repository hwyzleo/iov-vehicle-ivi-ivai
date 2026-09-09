package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

/**
 * CR-019 新增错误码（温度语义边界 / 定性幅度 / 位置拓扑 / Tool RAG）。
 *
 * 与 CR-016 的 IVAI-L0-SLOT-001、CR-017 的 IVAI-ALIAS-TOPOLOGY-001、CR-018 的
 * IVAI-RAG-BOUNDARY-001 并存；汇总码语义不变（Trace / 导出辅助字段记录细分码）。
 *
 * IVAI-TEST-OUTCOME-001 / IVAI-TEST-COMPARATOR-001 属测试评分契约，定义在
 * app-demo 的 TestErrorCode（见 IVI-IVAI-DSN-CR-019 新增错误码表）。
 */
object Cr019ErrorCodes {

    /** 无法区分相对调整与绝对目标（语义歧义，进入追问）。 */
    const val TEMP_SEMANTIC = "IVAI-TEMP-SEMANTIC-001"

    /** 绝对温度超出当前车型允许范围（拒绝执行）。 */
    const val TEMP_RANGE = "IVAI-TEMP-RANGE-001"

    /** 定性幅度无法映射或车型不适用（进入追问/降级）。 */
    const val TEMP_DELTA = "IVAI-TEMP-DELTA-001"

    /** 当前车型不支持请求温区（不得误执行）。 */
    const val TEMP_ZONE_UNSUPPORTED = "IVAI-TEMP-ZONE-UNSUPPORTED-001"

    /** 温度 Tool 检索文档缺少操作/数值角色边界（构建校验失败）。 */
    const val TEMP_RAG = "IVAI-TEMP-RAG-001"
}
