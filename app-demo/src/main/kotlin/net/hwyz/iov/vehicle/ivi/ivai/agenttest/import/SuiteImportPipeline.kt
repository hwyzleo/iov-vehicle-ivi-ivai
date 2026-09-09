package net.hwyz.iov.vehicle.ivi.ivai.agenttest.import

import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AgentTestCaseRepository
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.ValidatedAgentTestSuite

/**
 * 导入编排（IVI-IVAI-DSN-CR-015，纯 JVM，可单元测试）：
 *
 *   Guard（字节大小）→ Parser（两段式）→ Validator（整包校验）→
 *   Fingerprint（SHA-256）→ Repository.activateImportedSuite（原子写 + 重载）。
 *
 * 任一步失败返回 [SuiteImportResult.Failure]，不修改当前激活 Suite 与页面列表；
 * 整包成功或整包失败，不跳过非法行后继续导入。
 */
class SuiteImportPipeline(
    val guard: ImportSizeGuard = ImportSizeGuard(),
    private val parser: AgentTestSuiteParser = AgentTestSuiteParser(),
    private val validator: AgentTestSuiteValidator = AgentTestSuiteValidator(),
    private val repository: AgentTestCaseRepository
) {

    fun import(bytes: ByteArray): SuiteImportResult {
        // 防御：字节级大小再校验（ContentResolver 读取阶段已由 guard.readLimited 限制）。
        if (bytes.size.toLong() > guard.maxFileBytes) {
            return SuiteImportResult.Failure(
                TestErrorCode.IMPORT_SIZE_EXCEEDED,
                "文件超过大小上限 ${guard.maxFileBytes} 字节"
            )
        }

        // 1) 解析（IMPORT-003 / IMPORT-005）。
        val suite = try {
            parser.parse(bytes)
        } catch (e: SuiteImportException) {
            return SuiteImportResult.Failure(e.errorCode, e.message ?: "Suite 解析失败")
        }

        // 2) 整包校验（IMPORT-004 / IMPORT-005）。
        val validation = validator.validate(suite)
        if (!validation.valid) {
            val message = validation.fatalErrorMessage
                ?: "Suite 校验失败（${validation.caseIssues.size} 条非法用例）：" +
                    (validation.caseIssues.entries.firstOrNull()?.value ?: "")
            return SuiteImportResult.Failure(
                validation.fatalErrorCode ?: TestErrorCode.IMPORT_VALIDATION_FAILED,
                message
            )
        }

        // 3) 指纹 + 原子激活（IMPORT-006）。
        val sha256 = SuiteFingerprint.sha256(bytes)
        val validated = ValidatedAgentTestSuite(bytes, suite, validation, sha256)
        return try {
            val loaded = repository.activateImportedSuite(validated)
            SuiteImportResult.Success(loaded, validated.descriptor())
        } catch (e: SuiteImportException) {
            SuiteImportResult.Failure(e.errorCode, e.message ?: "激活失败")
        }
    }
}
