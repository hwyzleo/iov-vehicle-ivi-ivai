package net.hwyz.iov.vehicle.ivi.ivai.agenttest.import

import java.io.ByteArrayInputStream
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * CR-015 导入资源边界单测：受限读取、文件大小、字符串长度与嵌套深度限制。
 */
class ImportSizeGuardTest {

    @Test
    fun `受限读取未超限返回完整内容`() {
        val guard = ImportSizeGuard(maxFileBytes = 100)
        val bytes = "hello".toByteArray()
        assertArrayEquals(bytes, guard.readLimited(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `恰好等于上限可读取`() {
        val guard = ImportSizeGuard(maxFileBytes = 100)
        val bytes = ByteArray(100)
        assertEquals(100, guard.readLimited(ByteArrayInputStream(bytes)).size)
    }

    @Test
    fun `超过上限 → IMPORT-002`() {
        val guard = ImportSizeGuard(maxFileBytes = 100)
        val e = assertThrows(SuiteImportException::class.java) {
            guard.readLimited(ByteArrayInputStream(ByteArray(200)))
        }
        assertEquals(TestErrorCode.IMPORT_SIZE_EXCEEDED, e.errorCode)
    }

    @Test
    fun `字符串长度超限 → IMPORT-005`() {
        val guard = ImportSizeGuard(maxStringLength = 5)
        val e = assertThrows(SuiteImportException::class.java) {
            guard.checkStringLength("abcdef", "input")
        }
        assertEquals(TestErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode)
    }

    @Test
    fun `嵌套深度超限 → IMPORT-005`() {
        val guard = ImportSizeGuard(maxJsonDepth = 2)
        val deep = kotlinx.serialization.json.buildJsonObject {
            put(
                "a",
                kotlinx.serialization.json.buildJsonObject {
                    put(
                        "b",
                        kotlinx.serialization.json.buildJsonObject {
                            put("c", kotlinx.serialization.json.JsonPrimitive(1))
                        }
                    )
                }
            )
        }
        val e = assertThrows(SuiteImportException::class.java) {
            guard.checkJsonDepth(deep, "expectedArguments")
        }
        assertEquals(TestErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode)
    }
}
