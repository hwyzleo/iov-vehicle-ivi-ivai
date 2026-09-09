package net.hwyz.iov.vehicle.ivi.ivai.agenttest.import

import java.io.InputStream
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestSuite

/**
 * 导入资源边界（IVI-IVAI-DSN-CR-015 安全与资源边界）。
 *
 * 限制文件大小、字符串长度与 JSON 嵌套深度，避免内存或解析资源耗尽。
 *  - 文件大小超限 → [TestErrorCode.IMPORT_SIZE_EXCEEDED]
 *  - 字符串长度 / 嵌套深度超限 → [TestErrorCode.IMPORT_VALIDATION_FAILED]
 */
class ImportSizeGuard(
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    val maxStringLength: Int = DEFAULT_MAX_STRING_LENGTH,
    val maxJsonDepth: Int = DEFAULT_MAX_JSON_DEPTH
) {

    /**
     * 受限读取：逐块累计字节，超过 [maxFileBytes] 时抛出 IMPORT-002。
     * 用于 ContentResolver 读取阶段，避免一次性把超大文件载入内存。
     */
    fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(READ_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxFileBytes) {
                throw SuiteImportException(
                    TestErrorCode.IMPORT_SIZE_EXCEEDED,
                    "文件超过大小上限 ${maxFileBytes} 字节"
                )
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 字符串长度超限抛 IMPORT-005（校验失败）。 */
    fun checkStringLength(value: String, field: String) {
        if (value.length > maxStringLength) {
            throw SuiteImportException(
                TestErrorCode.IMPORT_VALIDATION_FAILED,
                "$field 超过字符串长度上限 $maxStringLength"
            )
        }
    }

    /** JSON 嵌套深度超限抛 IMPORT-005（校验失败）。 */
    fun checkJsonDepth(
        element: kotlinx.serialization.json.JsonElement,
        field: String
    ) {
        val depth = depthOf(element)
        if (depth > maxJsonDepth) {
            throw SuiteImportException(
                TestErrorCode.IMPORT_VALIDATION_FAILED,
                "$field JSON 嵌套深度 $depth 超过上限 $maxJsonDepth"
            )
        }
    }

    private fun depthOf(element: kotlinx.serialization.json.JsonElement): Int = when (element) {
        is kotlinx.serialization.json.JsonObject ->
            (element.values.maxOfOrNull { depthOf(it) } ?: 0) + 1
        is kotlinx.serialization.json.JsonArray ->
            (element.maxOfOrNull { depthOf(it) } ?: 0) + 1
        else -> 1
    }

    companion object {
        /** 首期文件大小默认上限：10 MiB。 */
        const val DEFAULT_MAX_FILE_BYTES = 10L * 1024 * 1024

        /** 单字段字符串长度上限。 */
        const val DEFAULT_MAX_STRING_LENGTH = 100_000

        /** JSON 嵌套深度上限。 */
        const val DEFAULT_MAX_JSON_DEPTH = 64

        private const val READ_BUFFER_SIZE = 64 * 1024
    }
}

/** 便捷：校验整个 Suite 的资源边界（由 Validator 调用）。 */
fun AgentTestSuite.checkResourceLimits(guard: ImportSizeGuard) {
    guard.checkStringLength(suiteId, "suiteId")
    governanceVersion?.let { guard.checkStringLength(it, "governanceVersion") }
    cases.forEach { case ->
        guard.checkStringLength(case.caseId, "caseId")
        case.description?.let { guard.checkStringLength(it, "description") }
        case.tags.forEach { guard.checkStringLength(it, "tags") }
        guard.checkStringLength(case.input, "input")
        guard.checkStringLength(case.expectedCapabilityPack, "expectedCapabilityPack")
        guard.checkJsonDepth(case.expectedArguments, "expectedArguments")
    }
}
