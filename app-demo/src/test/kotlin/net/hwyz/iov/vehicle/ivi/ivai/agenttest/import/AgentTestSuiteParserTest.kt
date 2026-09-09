package net.hwyz.iov.vehicle.ivi.ivai.agenttest.import

import net.hwyz.iov.vehicle.ivi.ivai.agenttest.TestSuiteFixtures
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * CR-015 解析器单测：两段式解析、错误码分类、可选字段向后兼容与顺序保持。
 */
class AgentTestSuiteParserTest {

    private val parser = AgentTestSuiteParser()

    @Test
    fun `合法 Suite 解析且保持声明顺序`() {
        val suite = parser.parse(TestSuiteFixtures.validSuiteRaw.toByteArray())
        assertEquals("suite-test-v1", suite.suiteId)
        assertEquals(1, suite.schemaVersion)
        assertEquals("ivai-governance-v1-draft", suite.governanceVersion)
        assertEquals(listOf("CASE-001", "CASE-002"), suite.cases.map { it.caseId })
    }

    @Test
    fun `JSON 语法非法 → IMPORT-003`() {
        val e = assertThrows(SuiteImportException::class.java) {
            parser.parse("{ not json".toByteArray())
        }
        assertEquals(TestErrorCode.IMPORT_INVALID_JSON, e.errorCode)
    }

    @Test
    fun `非法枚举导致 Schema 解码失败 → IMPORT-005`() {
        val raw = TestSuiteFixtures.validSuiteRaw.replace("CABIN_COMFORT", "NOT_A_DOMAIN")
        val e = assertThrows(SuiteImportException::class.java) {
            parser.parse(raw.toByteArray())
        }
        assertEquals(TestErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode)
    }

    @Test
    fun `未知字段容忍且可选字段缺失兼容`() {
        // 顶层未知字段被忽略（ignoreUnknownKeys）。
        val raw = TestSuiteFixtures.validSuiteRaw.replace(
            "\"schemaVersion\": 1,",
            "\"schemaVersion\": 1, \"extraTopField\": 123,"
        )
        val suite = parser.parse(raw.toByteArray())
        assertEquals(2, suite.cases.size)
        // 夹具本就不含 description / tags 等可选字段，解析成功即向后兼容。
        assertEquals("CASE-001", suite.cases[0].caseId)
        assertEquals("CASE-002", suite.cases[1].caseId)
    }
}
