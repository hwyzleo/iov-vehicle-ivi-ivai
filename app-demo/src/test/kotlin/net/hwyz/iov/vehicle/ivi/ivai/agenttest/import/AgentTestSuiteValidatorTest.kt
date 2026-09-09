package net.hwyz.iov.vehicle.ivi.ivai.agenttest.import

import net.hwyz.iov.vehicle.ivi.ivai.agenttest.TestSuiteFixtures
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-015 校验器单测：整包致命项与逐条非法项、错误码分类、资源边界。
 */
class AgentTestSuiteValidatorTest {

    private val parser = AgentTestSuiteParser()

    private fun validate(raw: String) = AgentTestSuiteValidator().validate(parser.parse(raw))

    @Test
    fun `合法 Suite 通过整包校验`() {
        val result = validate(TestSuiteFixtures.validSuiteRaw)
        assertTrue(result.valid)
        assertEquals(null, result.fatalErrorCode)
        assertTrue(result.caseIssues.isEmpty())
    }

    @Test
    fun `schemaVersion 不支持 → IMPORT-004 整包拒绝`() {
        val raw = TestSuiteFixtures.validSuiteRaw.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val result = validate(raw)
        assertFalse(result.valid)
        assertEquals(TestErrorCode.IMPORT_UNSUPPORTED_VERSION, result.fatalErrorCode)
    }

    @Test
    fun `suiteId 为空 → IMPORT-005 整包拒绝`() {
        val raw = TestSuiteFixtures.validSuiteRaw.replace("\"suiteId\": \"suite-test-v1\"", "\"suiteId\": \"\"")
        val result = validate(raw)
        assertFalse(result.valid)
        assertEquals(TestErrorCode.IMPORT_VALIDATION_FAILED, result.fatalErrorCode)
    }

    @Test
    fun `cases 为空 → IMPORT-005 整包拒绝`() {
        val raw = """{"suiteId": "x", "schemaVersion": 1, "cases": []}"""
        val result = validate(raw)
        assertFalse(result.valid)
        assertEquals(TestErrorCode.IMPORT_VALIDATION_FAILED, result.fatalErrorCode)
        assertTrue(result.fatalErrorMessage!!.contains("cases 为空"))
    }

    @Test
    fun `caseId 重复 → 整包校验失败且标记非法项`() {
        val raw = TestSuiteFixtures.validSuiteRaw.replace("\"caseId\": \"CASE-002\"", "\"caseId\": \"CASE-001\"")
        val result = validate(raw)
        assertFalse(result.valid)
        assertEquals(null, result.fatalErrorCode)
        assertTrue(result.caseIssues.containsKey("CASE-001"))
        assertTrue(result.caseIssues.getValue("CASE-001").contains("重复"))
    }

    @Test
    fun `input 为空 → 校验失败`() {
        val raw = TestSuiteFixtures.validSuiteRaw.replace("\"input\": \"打开空调\"", "\"input\": \"  \"")
        val result = validate(raw)
        assertFalse(result.valid)
        assertTrue(result.caseIssues.getValue("CASE-001").contains("input 为空"))
    }

    @Test
    fun `能力包为空 → 校验失败`() {
        val raw = TestSuiteFixtures.validSuiteRaw.replace(
            "\"expectedCapabilityPack\": \"cabin.climate\"",
            "\"expectedCapabilityPack\": \"\""
        )
        val result = validate(raw)
        assertFalse(result.valid)
        assertEquals(2, result.caseIssues.size)
    }

    @Test
    fun `目标 ID 格式非法 → 校验失败`() {
        val raw = TestSuiteFixtures.validSuiteRaw.replace("\"id\": \"climate.power.set\"", "\"id\": \"bad id!\"")
        val result = validate(raw)
        assertFalse(result.valid)
        assertTrue(result.caseIssues.getValue("CASE-001").contains("格式"))
    }

    @Test
    fun `cases 数量超过安全上限 → IMPORT-005 整包拒绝`() {
        val guard = ImportSizeGuard()
        val validator = AgentTestSuiteValidator(guard, maxCaseCount = 5)
        // 构造 6 条用例。
        val many = buildString {
            append("""{"suiteId": "s", "schemaVersion": 1, "cases": [""")
            repeat(6) {
                if (it > 0) append(",")
                append(
                    """{"caseId": "C-$it", "input": "打开空调", "expectedTier": "L0_DETERMINISTIC_TOOL", """ +
                        """"expectedDomain": "CABIN_COMFORT", "expectedCapabilityPack": "cabin.climate", """ +
                        """"expectedTarget": {"type": "TOOL", "id": "climate.power.set"}, "expectedArguments": {"enabled": true}}"""
                )
            }
            append("]}")
        }
        val result = validator.validate(parser.parse(many))
        assertFalse(result.valid)
        assertEquals(TestErrorCode.IMPORT_VALIDATION_FAILED, result.fatalErrorCode)
        assertTrue(result.fatalErrorMessage!!.contains("超过安全上限"))
    }

    @Test
    fun `字符串长度超限 → IMPORT-005 整包拒绝`() {
        val guard = ImportSizeGuard(maxStringLength = 15)
        val validator = AgentTestSuiteValidator(guard)
        // 无 governanceVersion，suiteId 短；input 超长触发限制。
        val raw = """{
          "suiteId": "s",
          "schemaVersion": 1,
          "cases": [
            {
              "caseId": "C-1",
              "input": "这是一个非常长的输入文本用于触发字符串长度限制超过十五个字符",
              "expectedTier": "L0_DETERMINISTIC_TOOL",
              "expectedDomain": "CABIN_COMFORT",
              "expectedCapabilityPack": "cabin.climate"
            }
          ]
        }"""
        val result = validator.validate(parser.parse(raw))
        assertFalse(result.valid)
        assertEquals(TestErrorCode.IMPORT_VALIDATION_FAILED, result.fatalErrorCode)
        assertTrue(result.fatalErrorMessage!!.contains("input 超过字符串长度上限"))
    }

    @Test
    fun `JSON 嵌套深度超限 → IMPORT-005 整包拒绝`() {
        val guard = ImportSizeGuard(maxJsonDepth = 2)
        val validator = AgentTestSuiteValidator(guard)
        val raw = TestSuiteFixtures.validSuiteRaw.replace(
            "\"expectedArguments\": { \"enabled\": true }",
            "\"expectedArguments\": { \"a\": { \"b\": { \"c\": 1 } } }"
        )
        val result = validator.validate(parser.parse(raw))
        assertFalse(result.valid)
        assertEquals(TestErrorCode.IMPORT_VALIDATION_FAILED, result.fatalErrorCode)
        assertTrue(result.fatalErrorMessage!!.contains("嵌套深度"))
    }
}
