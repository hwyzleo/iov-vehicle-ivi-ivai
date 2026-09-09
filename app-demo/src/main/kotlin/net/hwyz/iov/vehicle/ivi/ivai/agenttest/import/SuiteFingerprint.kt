package net.hwyz.iov.vehicle.ivi.ivai.agenttest.import

import java.security.MessageDigest

/**
 * Suite 内容指纹（IVI-IVAI-DSN-CR-015）。
 *
 * 对导入文件原始字节计算 SHA-256，供激活存储写后重读校验、Repository 加载一致性
 * 校验与批次冻结（suiteId + schemaVersion + sha256）。
 */
object SuiteFingerprint {

    /** SHA-256 十六进制小写摘要。 */
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 字节内容与期望摘要是否一致（忽略大小写）。 */
    fun matches(bytes: ByteArray, expectedHex: String): Boolean =
        sha256(bytes).equals(expectedHex, ignoreCase = true)
}
