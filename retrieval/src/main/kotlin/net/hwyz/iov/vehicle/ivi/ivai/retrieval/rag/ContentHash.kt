package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

/**
 * 稳定内容哈希（CR-011）。
 *
 * 用于 RetrievalDocument / KnowledgeChunk / 索引 contentSetHash 的确定性哈希：
 * 不依赖 JVM hashCode，跨进程可复现，保证"治理内容少量变化且兼容键不变时可按
 * contentHash 增量更新"可被正确比较。
 */
object ContentHash {

    /**
     * FNV-1a 64 稳定散列，输出 16 位十六进制小写。
     * 与 CR-010 RuntimeCapabilitySet.stableHash 同源（各自实现，语义一致）。
     */
    fun stableHash(input: String): String {
        var hash = -0x340d631b7bdddcdbL // FNV offset basis
        val bytes = input.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            hash = hash xor (b.toLong() and 0xff)
            hash *= 0x100000001b3L // FNV prime
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }

    /** 将多个语义段拼成稳定哈希（顺序敏感，去重前请自行排序）。 */
    fun of(vararg parts: String): String = stableHash(parts.joinToString("\u0000"))
}
