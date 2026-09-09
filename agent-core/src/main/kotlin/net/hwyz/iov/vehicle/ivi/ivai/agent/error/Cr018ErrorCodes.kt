package net.hwyz.iov.vehicle.ivi.ivai.agent.error

/**
 * CR-018 新增错误码（座舱气流领域路由与空调相似 Tool 边界）。
 *
 * 与 CR-016 的 IVAI-STATE-001 / IVAI-CANDIDATE-001、CR-017 的
 * IVAI-ALIAS-AMBIGUOUS-001 等细分码并存；汇总码语义不变（Trace / 导出辅助
 * 字段记录细分码）。
 */
object Cr018ErrorCodes {

    /** 座舱气流对象未能形成 CABIN_COMFORT 证据（领域路由盲区）。 */
    const val DOMAIN_AIRFLOW = "IVAI-DOMAIN-AIRFLOW-001"

    /** 存在合法本地候选却错误转入 L3（禁止无原因直入 L3）。 */
    const val ROUTE_LOCAL = "IVAI-ROUTE-LOCAL-001"
}
