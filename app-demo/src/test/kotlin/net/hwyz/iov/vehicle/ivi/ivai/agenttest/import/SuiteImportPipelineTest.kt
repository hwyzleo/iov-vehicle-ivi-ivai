package net.hwyz.iov.vehicle.ivi.ivai.agenttest.import

import java.io.File
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.TestSuiteFixtures
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.ActiveFileSuiteSource
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.ActiveSuiteStore
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AgentTestCaseLoader
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AgentTestCaseRepository
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.BuiltInSuiteSource
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.SuiteSourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * CR-015 导入管线单测：整包成功 / 整包失败语义、错误码映射、失败不修改当前
 * Suite、原子激活失败（IMPORT-006）。
 */
class SuiteImportPipelineTest {

    @TempDir
    lateinit var tempDir: File

    private fun repository(storeDir: File = File(tempDir, "active")): AgentTestCaseRepository =
        AgentTestCaseRepository(
            builtIn = BuiltInSuiteSource(AgentTestCaseLoader { TestSuiteFixtures.builtinSuiteRaw }),
            active = ActiveFileSuiteSource(ActiveSuiteStore(storeDir)),
            parser = AgentTestSuiteParser(),
            validator = AgentTestSuiteValidator()
        )

    @Test
    fun `合法文件整包导入成功并激活`() {
        val repo = repository()
        val pipeline = SuiteImportPipeline(repository = repo)

        val result = pipeline.import(TestSuiteFixtures.validSuiteRaw.toByteArray())
        assertTrue(result is SuiteImportResult.Success)
        val success = result as SuiteImportResult.Success
        assertEquals("suite-test-v1", success.loaded.suite?.suiteId)
        assertEquals(SuiteSourceType.ACTIVE_FILE, success.loaded.source)
        assertEquals("suite-test-v1", success.descriptor.suiteId)
        assertEquals(2, success.descriptor.caseCount)
        // 仓库后续加载也命中激活文件。
        val reloaded = repo.loadActiveSuite()
        assertEquals("suite-test-v1", reloaded.suite?.suiteId)
        assertEquals(SuiteSourceType.ACTIVE_FILE, reloaded.source)
    }

    @Test
    fun `JSON 语法非法 → Failure 003 且当前 Suite 不变`() {
        val repo = repository()
        val pipeline = SuiteImportPipeline(repository = repo)

        val result = pipeline.import("{ not json".toByteArray())
        assertTrue(result is SuiteImportResult.Failure)
        assertEquals(TestErrorCode.IMPORT_INVALID_JSON, (result as SuiteImportResult.Failure).errorCode)
        // 当前激活保持内置。
        assertEquals("builtin-suite", repo.loadActiveSuite().suite?.suiteId)
    }

    @Test
    fun `非法用例（重复 caseId）→ Failure 005 整包拒绝且列表不变`() {
        val repo = repository()
        val pipeline = SuiteImportPipeline(repository = repo)
        val dup = TestSuiteFixtures.validSuiteRaw.replace(
            "\"caseId\": \"CASE-002\"",
            "\"caseId\": \"CASE-001\""
        )
        val result = pipeline.import(dup.toByteArray())
        assertTrue(result is SuiteImportResult.Failure)
        assertEquals(TestErrorCode.IMPORT_VALIDATION_FAILED, (result as SuiteImportResult.Failure).errorCode)
        assertEquals("builtin-suite", repo.loadActiveSuite().suite?.suiteId)
    }

    @Test
    fun `schemaVersion 不支持 → Failure 004`() {
        val repo = repository()
        val pipeline = SuiteImportPipeline(repository = repo)
        val raw = TestSuiteFixtures.validSuiteRaw.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val result = pipeline.import(raw.toByteArray())
        assertTrue(result is SuiteImportResult.Failure)
        assertEquals(TestErrorCode.IMPORT_UNSUPPORTED_VERSION, (result as SuiteImportResult.Failure).errorCode)
    }

    @Test
    fun `文件超过大小上限 → Failure 002`() {
        val repo = repository()
        val pipeline = SuiteImportPipeline(guard = ImportSizeGuard(maxFileBytes = 100), repository = repo)
        val result = pipeline.import(TestSuiteFixtures.validSuiteRaw.toByteArray())
        assertTrue(result is SuiteImportResult.Failure)
        assertEquals(TestErrorCode.IMPORT_SIZE_EXCEEDED, (result as SuiteImportResult.Failure).errorCode)
        assertEquals("builtin-suite", repo.loadActiveSuite().suite?.suiteId)
    }

    @Test
    fun `激活写入失败 → Failure 006 且内置保持可加载`() {
        // 激活目录位置被普通文件占位 → 原子写入失败。
        val blocker = File(tempDir, "active")
        blocker.writeText("i am a file")
        val repo = repository(blocker)
        val pipeline = SuiteImportPipeline(repository = repo)

        val result = pipeline.import(TestSuiteFixtures.validSuiteRaw.toByteArray())
        assertTrue(result is SuiteImportResult.Failure)
        assertEquals(TestErrorCode.IMPORT_ACTIVATION_FAILED, (result as SuiteImportResult.Failure).errorCode)
        // 未写入任何激活文件，内置保持。
        assertEquals("builtin-suite", repo.loadActiveSuite().suite?.suiteId)
    }
}
