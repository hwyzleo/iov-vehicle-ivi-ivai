package net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository

import java.io.File
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.TestSuiteFixtures
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.AgentTestSuiteParser
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.AgentTestSuiteValidator
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.SuiteFingerprint
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.SuiteValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ActiveSuiteDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * CR-012 测试资产仓库单测：JSON 解析、版本、重复 ID、非法字段与稳定顺序
 * （IVAI-REQ-111 / IVAI-TEST-001 / IVAI-TEST-002）。
 */
class AgentTestCaseRepositoryTest {

    private fun repository(raw: String?) = AgentTestCaseRepository.builtInOnly(AgentTestCaseLoader { raw })

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
        val result = repository(validSuite).loadActiveSuite()
        assertNull(result.errorCode)
        assertEquals("suite-test-v1", result.suite?.suiteId)
        assertEquals("ivai-governance-v1-draft", result.suite?.governanceVersion)
        assertEquals(listOf("CASE-001", "CASE-002"), result.validCases.map { it.caseId })
        assertTrue(result.invalidCases.isEmpty())
    }

    @Test
    fun `空资产或缺失资产 → SUITE_INVALID`() {
        val missing = repository(null).loadActiveSuite()
        assertEquals(TestErrorCode.SUITE_INVALID, missing.errorCode)

        val blank = repository("   ").loadActiveSuite()
        assertEquals(TestErrorCode.SUITE_INVALID, blank.errorCode)
    }

    @Test
    fun `JSON 解析失败 → SUITE_INVALID 且不允许开始`() {
        val result = repository("{ not json").loadActiveSuite()
        assertEquals(TestErrorCode.SUITE_INVALID, result.errorCode)
        assertNull(result.suite)
        assertTrue(result.hasFatalError)
    }

    @Test
    fun `schemaVersion 不支持 → SUITE_INVALID`() {
        val raw = validSuite.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        assertEquals(TestErrorCode.SUITE_INVALID, repository(raw).loadActiveSuite().errorCode)
    }

    @Test
    fun `suiteId 为空 → SUITE_INVALID`() {
        val raw = validSuite.replace("\"suiteId\": \"suite-test-v1\"", "\"suiteId\": \"\"")
        assertEquals(TestErrorCode.SUITE_INVALID, repository(raw).loadActiveSuite().errorCode)
    }

    @Test
    fun `非法枚举值导致 Suite 整体无法解析 → SUITE_INVALID`() {
        val raw = validSuite.replace("CABIN_COMFORT", "NOT_A_DOMAIN")
        val result = repository(raw).loadActiveSuite()
        assertEquals(TestErrorCode.SUITE_INVALID, result.errorCode)
        assertNull(result.suite)
    }

    @Test
    fun `caseId 重复 → 后出现的标记 INVALID 跳过，其他用例正常加载`() {
        val raw = validSuite.replace("\"caseId\": \"CASE-002\"", "\"caseId\": \"CASE-001\"")
        val result = repository(raw).loadActiveSuite()
        assertNull(result.errorCode)
        assertEquals(listOf("CASE-001"), result.validCases.map { it.caseId })
        assertEquals(setOf("CASE-001"), result.invalidCases.keys)
        assertTrue(result.invalidCases.getValue("CASE-001").contains("重复"))
    }

    @Test
    fun `input 为空 → INVALID 跳过`() {
        val raw = validSuite.replace("\"input\": \"打开空调\"", "\"input\": \"  \"")
        val result = repository(raw).loadActiveSuite()
        assertNull(result.errorCode)
        assertEquals(listOf("CASE-002"), result.validCases.map { it.caseId })
        assertEquals(setOf("CASE-001"), result.invalidCases.keys)
    }

    @Test
    fun `能力包为空 → INVALID 跳过`() {
        val raw = validSuite.replace("\"expectedCapabilityPack\": \"cabin.climate\"", "\"expectedCapabilityPack\": \"\"")
        val result = repository(raw).loadActiveSuite()
        assertEquals(2, result.invalidCases.size)
        assertEquals(0, result.validCases.size)
    }

    @Test
    fun `目标 ID 格式非法 → INVALID 跳过`() {
        val raw = validSuite.replace("\"id\": \"climate.power.set\"", "\"id\": \"bad id!\"")
        val result = repository(raw).loadActiveSuite()
        assertNull(result.errorCode)
        assertEquals(listOf("CASE-002"), result.validCases.map { it.caseId })
        assertTrue(result.invalidCases.getValue("CASE-001").contains("格式"))
    }

    @Test
    fun `单条非法不阻止其他合法用例加载`() {
        val raw = validSuite
            .replace("\"input\": \"打开空调\"", "\"input\": \"\"")
            .replace("\"caseId\": \"CASE-002\"", "\"caseId\": \"CASE-001\"")
        val result = repository(raw).loadActiveSuite()
        assertNull(result.errorCode)
        // 两条均非法（input 空 + 重复 caseId）→ validCases 为空，但 suite 已解析、无整体错误。
        assertTrue(result.validCases.isEmpty())
        assertEquals(2, result.verdicts.count { !it.isValid })
        assertEquals(0, result.verdicts.count { it.isValid })
    }

    // ---- IVI-IVAI-DSN-CR-015：加载优先级与回退 ----

    @TempDir
    lateinit var tempDir: File

    /** 带激活存储的仓库：内置 + 激活文件双数据源。 */
    private fun dualRepository(
        builtInRaw: String?,
        storeDir: File = File(tempDir, "active")
    ): Pair<AgentTestCaseRepository, ActiveSuiteStore> {
        val store = ActiveSuiteStore(storeDir)
        val repo = AgentTestCaseRepository(
            builtIn = BuiltInSuiteSource(AgentTestCaseLoader { builtInRaw }),
            active = ActiveFileSuiteSource(store),
            parser = AgentTestSuiteParser(),
            validator = AgentTestSuiteValidator()
        )
        return repo to store
    }

    private fun descriptorOf(bytes: ByteArray, suiteId: String, caseCount: Int) =
        ActiveSuiteDescriptor(
            suiteId = suiteId,
            schemaVersion = 1,
            governanceVersion = "ivai-governance-v1-draft",
            caseCount = caseCount,
            sha256 = SuiteFingerprint.sha256(bytes),
            importedAtEpochMs = 1L
        )

    @Test
    fun `激活 Suite 有效时优先加载且来源 ACTIVE_FILE`() {
        val (repo, store) = dualRepository(TestSuiteFixtures.builtinSuiteRaw)
        val activeBytes = TestSuiteFixtures.validSuiteRaw.toByteArray()
        store.activate(activeBytes, descriptorOf(activeBytes, "suite-test-v1", 2))

        val result = repo.loadActiveSuite()
        assertNull(result.errorCode)
        assertEquals("suite-test-v1", result.suite?.suiteId)
        assertEquals(SuiteSourceType.ACTIVE_FILE, result.source)
        assertEquals(listOf("CASE-001", "CASE-002"), result.validCases.map { it.caseId })
        assertNull(result.degradedReason)
        assertTrue(result.sha256 == SuiteFingerprint.sha256(activeBytes))
    }

    @Test
    fun `激活文件 Hash 与描述符不一致 → 回退内置并记录原因`() {
        // 手工构造损坏状态：内容与描述符 Hash 不一致（绕过 activate 的写后校验）。
        val dir = File(tempDir, "active").apply { mkdirs() }
        val activeBytes = TestSuiteFixtures.validSuiteRaw.toByteArray()
        File(dir, "agent-regression.json").writeBytes(activeBytes)
        File(dir, "active-suite.json").writeText(
            kotlinx.serialization.json.Json.encodeToString(
                ActiveSuiteDescriptor.serializer(),
                descriptorOf(activeBytes, "suite-test-v1", 2).copy(sha256 = "deadbeef")
            )
        )
        val (repo, _) = dualRepository(TestSuiteFixtures.builtinSuiteRaw, dir)

        val result = repo.loadActiveSuite()
        assertNull(result.errorCode)
        assertEquals("builtin-suite", result.suite?.suiteId)
        assertEquals(SuiteSourceType.BUILT_IN, result.source)
        assertTrue(result.degradedReason != null && result.degradedReason.contains("已回退"))
    }

    @Test
    fun `激活描述符与内容不一致（caseCount 不符）→ 回退内置`() {
        val (repo, store) = dualRepository(TestSuiteFixtures.builtinSuiteRaw)
        val activeBytes = TestSuiteFixtures.validSuiteRaw.toByteArray()
        store.activate(activeBytes, descriptorOf(activeBytes, "suite-test-v1", caseCount = 999))

        val result = repo.loadActiveSuite()
        assertNull(result.errorCode)
        assertEquals("builtin-suite", result.suite?.suiteId)
        assertEquals(SuiteSourceType.BUILT_IN, result.source)
        assertTrue(result.degradedReason != null && result.degradedReason.contains("已回退"))
    }

    @Test
    fun `无激活文件 → 加载内置 且无降级提示`() {
        val (repo, _) = dualRepository(TestSuiteFixtures.builtinSuiteRaw)
        val result = repo.loadActiveSuite()
        assertNull(result.errorCode)
        assertEquals("builtin-suite", result.suite?.suiteId)
        assertEquals(SuiteSourceType.BUILT_IN, result.source)
        // 从未导入过激活 Suite：属正常默认状态，不产生降级提示。
        assertNull(result.degradedReason)
    }

    @Test
    fun `激活文件内容非法（解析失败）→ 回退内置并提示原因`() {
        val dir = File(tempDir, "active").apply { mkdirs() }
        val broken = "{ not json".toByteArray()
        File(dir, "agent-regression.json").writeBytes(broken)
        File(dir, "active-suite.json").writeText(
            kotlinx.serialization.json.Json.encodeToString(
                ActiveSuiteDescriptor.serializer(),
                descriptorOf(broken, "broken-suite", 1)
            )
        )
        val (repo, _) = dualRepository(TestSuiteFixtures.builtinSuiteRaw, dir)

        val result = repo.loadActiveSuite()
        assertNull(result.errorCode)
        assertEquals("builtin-suite", result.suite?.suiteId)
        assertEquals(SuiteSourceType.BUILT_IN, result.source)
        assertTrue(result.degradedReason != null && result.degradedReason.contains("已回退"))
    }

    @Test
    fun `activateImportedSuite 原子激活后优先加载新 Suite`() {
        val (repo, _) = dualRepository(TestSuiteFixtures.builtinSuiteRaw)
        val bytes = TestSuiteFixtures.validSuiteRaw.toByteArray()
        val validated = ValidatedAgentTestSuite(
            rawBytes = bytes,
            suite = AgentTestSuiteParser().parse(bytes),
            validation = SuiteValidationResult(valid = true),
            sha256 = SuiteFingerprint.sha256(bytes)
        )
        val loaded = repo.activateImportedSuite(validated)
        assertEquals("suite-test-v1", loaded.suite?.suiteId)
        assertEquals(SuiteSourceType.ACTIVE_FILE, loaded.source)
        // 后续加载也命中激活文件。
        val reloaded = repo.loadActiveSuite()
        assertEquals("suite-test-v1", reloaded.suite?.suiteId)
        assertEquals(SuiteSourceType.ACTIVE_FILE, reloaded.source)
    }
}

