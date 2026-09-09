package net.hwyz.iov.vehicle.ivi.ivai.agenttest.model

import kotlinx.serialization.Serializable

/**
 * 激活 Suite 描述符（IVI-IVAI-DSN-CR-015）。
 *
 * 记录激活文件的内容一致性信息，供 Repository 加载时校验 Hash / Schema / caseCount
 * 是否与激活文件一致；不一致时记录可读降级原因并回退内置 Suite（IVAI-TEST-IMPORT-008）。
 */
@Serializable
data class ActiveSuiteDescriptor(
    val suiteId: String,
    val schemaVersion: Int,
    val governanceVersion: String? = null,
    val caseCount: Int,
    val sha256: String,
    val importedAtEpochMs: Long
)
