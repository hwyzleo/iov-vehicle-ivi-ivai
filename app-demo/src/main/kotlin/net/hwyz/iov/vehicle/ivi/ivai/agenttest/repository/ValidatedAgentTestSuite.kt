package net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository

import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.SuiteValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ActiveSuiteDescriptor
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestSuite

/**
 * 已通过整包校验的导入 Suite（IVI-IVAI-DSN-CR-015）。
 *
 * 由 SuiteImportPipeline 生成，Repository.activateImportedSuite 消费（原子写入
 * 激活存储 + 重新加载）。
 */
data class ValidatedAgentTestSuite(
    val rawBytes: ByteArray,
    val suite: AgentTestSuite,
    val validation: SuiteValidationResult,
    val sha256: String
) {
    /** 生成激活描述符（供存储写入与导入结果回显）。 */
    fun descriptor(importedAtEpochMs: Long = System.currentTimeMillis()): ActiveSuiteDescriptor =
        ActiveSuiteDescriptor(
            suiteId = suite.suiteId,
            schemaVersion = suite.schemaVersion,
            governanceVersion = suite.governanceVersion,
            caseCount = suite.cases.size,
            sha256 = sha256,
            importedAtEpochMs = importedAtEpochMs
        )
}
