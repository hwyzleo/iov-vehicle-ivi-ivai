package net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository

import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-012 测试资产仓库单测：JSON 解析、版本、重复 ID、非法字段与稳定顺序
 * （IVAI-REQ-111 / IVAI-TEST-001 / IVAI-TEST-002）。
 */
class AgentTestCaseRepositoryTest {

    private fun repository(raw: String?) = AgentTestCaseRepository(AgentTestCaseLoader { raw })

    private val validSuite = """
        {
          "suiteId": "suite-test-v1",
          "schemaVersion": 1,
          "governanceVersion": "ivai-governance-v1-draft",
          "cases": [
            {
              "caseId": "CASE-001",
              "input": "打开空调",
              "expectedTier": "L0_DETERMINISTIC_TOOL",
              "expectedDomain": "CABIN_COMFORT",
              "expectedCapabilityPack": "cabin.climate",
              "expectedTarget": { "type": "TOOL", "id": "climate.power.set" },
              "expectedArguments": { "enabled": true }
            },
            {
              "caseId": "CASE-002",
              "input": "我有点冷",
              "expectedTier": "L1_LOCAL_TOOL_REASONING",
              "expectedDomain": "CABIN_COMFORT",
              "expectedCapabilityPack": "cabin.climate"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `合法 Suite 解析且保持声明顺序`() {
        val result = repository(validSuite).load()
        assertNull(result.errorCode)
        assertEquals("suite-test-v1", result.suite?.suiteId)
        assertEquals("ivai-governance-v1-draft", result.suite?.governanceVersion)
        assertEquals(listOf("CASE-001", "CASE-002"), result.validCases.map { it.caseId })
        assertTrue(result.invalidCases.isEmpty())
    }

    @Test
    fun `空资产或缺失资产 → SUITE_INVALID`() {
        val missing = repository(null).load()
        assertEquals(TestErrorCode.SUITE_INVALID, missing.errorCode)

        val blank = repository("   ").load()
        assertEquals(TestErrorCode.SUITE_INVALID, blank.errorCode)
    }

    @Test
    fun `JSON 解析失败 → SUITE_INVALID 且不允许开始`() {
        val result = repository("{ not json").load()
        assertEquals(TestErrorCode.SUITE_INVALID, result.errorCode)
        assertNull(result.suite)
        assertTrue(result.hasFatalError)
    }

    @Test
    fun `schemaVersion 不支持 → SUITE_INVALID`() {
        val raw = validSuite.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        assertEquals(TestErrorCode.SUITE_INVALID, repository(raw).load().errorCode)
    }

    @Test
    fun `suiteId 为空 → SUITE_INVALID`() {
        val raw = validSuite.replace("\"suiteId\": \"suite-test-v1\"", "\"suiteId\": \"\"")
        assertEquals(TestErrorCode.SUITE_INVALID, repository(raw).load().errorCode)
    }

    @Test
    fun `非法枚举值导致 Suite 整体无法解析 → SUITE_INVALID`() {
        val raw = validSuite.replace("CABIN_COMFORT", "NOT_A_DOMAIN")
        val result = repository(raw).load()
        assertEquals(TestErrorCode.SUITE_INVALID, result.errorCode)
        assertNull(result.suite)
    }

    @Test
    fun `caseId 重复 → 后出现的标记 INVALID 跳过，其他用例正常加载`() {
        val raw = validSuite.replace("\"caseId\": \"CASE-002\"", "\"caseId\": \"CASE-001\"")
        val result = repository(raw).load()
        assertNull(result.errorCode)
        assertEquals(listOf("CASE-001"), result.validCases.map { it.caseId })
        assertEquals(setOf("CASE-001"), result.invalidCases.keys)
        assertTrue(result.invalidCases.getValue("CASE-001").contains("重复"))
    }

    @Test
    fun `input 为空 → INVALID 跳过`() {
        val raw = validSuite.replace("\"input\": \"打开空调\"", "\"input\": \"  \"")
        val result = repository(raw).load()
        assertNull(result.errorCode)
        assertEquals(listOf("CASE-002"), result.validCases.map { it.caseId })
        assertEquals(setOf("CASE-001"), result.invalidCases.keys)
    }

    @Test
    fun `能力包为空 → INVALID 跳过`() {
        val raw = validSuite.replace("\"expectedCapabilityPack\": \"cabin.climate\"", "\"expectedCapabilityPack\": \"\"")
        val result = repository(raw).load()
        assertEquals(2, result.invalidCases.size)
        assertEquals(0, result.validCases.size)
    }

    @Test
    fun `目标 ID 格式非法 → INVALID 跳过`() {
        val raw = validSuite.replace("\"id\": \"climate.power.set\"", "\"id\": \"bad id!\"")
        val result = repository(raw).load()
        assertNull(result.errorCode)
        assertEquals(listOf("CASE-002"), result.validCases.map { it.caseId })
        assertTrue(result.invalidCases.getValue("CASE-001").contains("格式"))
    }

    @Test
    fun `单条非法不阻止其他合法用例加载`() {
        val raw = validSuite
            .replace("\"input\": \"打开空调\"", "\"input\": \"\"")
            .replace("\"caseId\": \"CASE-002\"", "\"caseId\": \"CASE-001\"")
        val result = repository(raw).load()
        assertNull(result.errorCode)
        // 两条均非法（input 空 + 重复 caseId）→ validCases 为空，但 suite 已解析、无整体错误。
        assertTrue(result.validCases.isEmpty())
        assertEquals(2, result.verdicts.count { !it.isValid })
        assertEquals(0, result.verdicts.count { it.isValid })
    }
}
