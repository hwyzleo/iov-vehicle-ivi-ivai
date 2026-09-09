package net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository

/**
 * 内置 Suite 数据源（IVI-IVAI-DSN-CR-015）。
 *
 * 加载随 APK debug assets 发布的版本化 JSON Suite；Release 不打包测试资产。
 * 资产保持只读，导入激活文件不覆盖 APK assets。
 */
class BuiltInSuiteSource(
    private val loader: AgentTestCaseLoader
) {
    /** 返回 Suite 原文；资产缺失或读取失败返回 null。 */
    fun loadRaw(): String? = loader.load()
}
