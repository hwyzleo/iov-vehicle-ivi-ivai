package net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository

import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.SuiteFingerprint

/**
 * 激活文件数据源（IVI-IVAI-DSN-CR-015）。
 *
 * 优先读取校验成功的激活 Suite；不存在或损坏时由 Repository 记录可读降级原因
 * 并回退内置 Suite（IVAI-TEST-IMPORT-008）。
 */
class ActiveFileSuiteSource(
    val store: ActiveSuiteStore
) {
    /** 激活存储读取结果（缺失 / 损坏 / 有效快照）。 */
    fun current(): ActiveStoreRead = store.current()

    /** 内容指纹（SHA-256）。 */
    fun fingerprintOf(bytes: ByteArray): String = SuiteFingerprint.sha256(bytes)
}
