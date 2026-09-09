package net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository

import java.nio.charset.StandardCharsets
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.AgentTestSuiteParser
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.AgentTestSuiteValidator
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.SuiteFingerprint
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.SuiteImportException
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestSuite

/**
 * 测试 Suite 原文加载器（IVI-IVAI-DSN-CR-012）。加载失败或资产缺失返回 null；
 * Android 实现从 debug assets 读取，纯 JVM 测试可注入字符串。
 */
fun interface AgentTestCaseLoader {
    fun load(): String?
}

/**
 * 单条用例判定（IVI-IVAI-DSN-CR-012）：合法或带非法原因的 INVALID 记录。
 * 逐条保留顺序；重复 caseId 时多条记录同 id，但只有重复项被标记非法。
 */
data class CaseVerdict(
    val case: AgentTestCase,
    val invalidReason: String? = null
) {
    val isValid: Boolean get() = invalidReason == null
}

/**
 * 本地测试资产仓库（IVI-IVAI-DSN-CR-012 / CR-015）。
 *
 * 加载优先级（CR-015）：存在激活 Suite 且 Hash / Schema / 描述符一致 → 加载
 * ActiveFileSuiteSource；激活 Suite 缺失或损坏 → 记录可读降级原因并回退
 * BuiltInSuiteSource。
 *
 * 内置路径保持 CR-012 容错语义（单条非法跳过并显示 INVALID）；导入路径整包成功
 * 或整包失败（由 SuiteImportPipeline 保证，仓库不产生部分导入）。
 */
class AgentTestCaseRepository(
    private val builtIn: BuiltInSuiteSource,
    private val active: ActiveFileSuiteSource?,
    private val parser: AgentTestSuiteParser,
    private val validator: AgentTestSuiteValidator
) {

    /**
     * 加载当前激活 Suite（CR-015 优先级：激活文件 → 内置）。
     */
    fun loadActiveSuite(): LoadedAgentTestSuite {
        // 1) 激活文件存在且一致 → ACTIVE_FILE。
        val activeLoaded = active?.let { loadActiveFile(it) }
        if (activeLoaded != null && !activeLoaded.hasFatalError) {
            return activeLoaded
        }

        // 2) 激活文件缺失或损坏 → 回退内置；仅真实降级（曾存在激活但不可用）记录可读原因。
        val degradedReason = activeLoaded
            ?.takeIf { it.hasFatalError }
            ?.let { "激活 Suite 不可用，已回退内置：${it.errorMessage}" }
        val builtInLoaded = loadBuiltIn()
        if (builtInLoaded.hasFatalError) return builtInLoaded
        return builtInLoaded.copy(
            source = SuiteSourceType.BUILT_IN,
            degradedReason = degradedReason
        )
    }

    /**
     * 激活导入 Suite（CR-015）：原子写入激活存储后重新加载（现在应命中激活文件）。
     * 写入失败抛 [SuiteImportException]（IVAI-TEST-IMPORT-006）。
     */
    fun activateImportedSuite(validated: ValidatedAgentTestSuite): LoadedAgentTestSuite {
        val activeSource = active
            ?: throw SuiteImportException(
                TestErrorCode.IMPORT_ACTIVATION_FAILED,
                "未配置激活存储，无法导入 Suite"
            )
        activeSource.store.activate(validated.rawBytes, validated.descriptor())
        return loadActiveSuite()
    }

    /** 加载激活文件；无激活文件返回 null；损坏返回带 IMPORT-008 错误的结果。 */
    private fun loadActiveFile(source: ActiveFileSuiteSource): LoadedAgentTestSuite? {
        when (val read = source.current()) {
            is ActiveStoreRead.Absent -> return null
            is ActiveStoreRead.Corrupt -> {
                return corrupt(
                    TestErrorCode.IMPORT_ACTIVE_CORRUPT_FALLBACK,
                    "激活 Suite 损坏：${read.reason}"
                )
            }
            is ActiveStoreRead.Snapshot -> {
                val snapshot = read.snapshot
                val descriptor = snapshot.descriptor

                // 1) 内容 Hash 校验。
                if (!SuiteFingerprint.matches(snapshot.bytes, descriptor.sha256)) {
                    return corrupt(
                        TestErrorCode.IMPORT_ACTIVE_CORRUPT_FALLBACK,
                        "激活 Suite 内容 Hash 与描述符不一致"
                    )
                }

                // 2) 解析 + 校验（与内置同一 Parser / Validator）。
                val raw = String(snapshot.bytes, StandardCharsets.UTF_8)
                val loaded = parseAndValidate(raw, SuiteSourceType.ACTIVE_FILE)
                if (loaded.hasFatalError) return loaded

                // 3) 描述符一致性：suiteId / schemaVersion / caseCount。
                val suite = loaded.suite ?: return loaded
                val schemaMismatch = descriptor.suiteId != suite.suiteId ||
                    descriptor.schemaVersion != suite.schemaVersion ||
                    descriptor.caseCount != suite.cases.size
                if (schemaMismatch) {
                    return corrupt(
                        TestErrorCode.IMPORT_ACTIVE_CORRUPT_FALLBACK,
                        "激活 Suite 描述符与内容不一致（suiteId / schemaVersion / caseCount）"
                    )
                }
                return loaded.copy(sha256 = descriptor.sha256)
            }
        }
    }

    private fun corrupt(errorCode: String, message: String) = LoadedAgentTestSuite(
        suite = null,
        errorCode = errorCode,
        errorMessage = message,
        source = SuiteSourceType.ACTIVE_FILE
    )

    private fun loadBuiltIn(): LoadedAgentTestSuite {
        val raw = builtIn.loadRaw()
        if (raw.isNullOrBlank()) {
            return LoadedAgentTestSuite(
                suite = null,
                errorCode = TestErrorCode.SUITE_INVALID,
                errorMessage = "测试资产缺失或为空（Release 构建不打包 debug assets）"
            )
        }
        return parseAndValidate(raw, SuiteSourceType.BUILT_IN)
    }

    /** 解析 + 校验。内置 / 激活路径把解析与整包校验错误映射为 SUITE_INVALID。 */
    private fun parseAndValidate(raw: String, source: SuiteSourceType): LoadedAgentTestSuite {
        val suite = try {
            parser.parse(raw)
        } catch (e: SuiteImportException) {
            return LoadedAgentTestSuite(
                suite = null,
                errorCode = TestErrorCode.SUITE_INVALID,
                errorMessage = "Suite JSON 解析失败：${e.message}",
                source = source
            )
        }
        val validation = validator.validate(suite)
        if (validation.fatalErrorCode != null) {
            return LoadedAgentTestSuite(
                suite = null,
                errorCode = TestErrorCode.SUITE_INVALID,
                errorMessage = validation.fatalErrorMessage ?: "Suite 校验失败",
                source = source
            )
        }
        // 单条非法用例 → INVALID 跳过（CR-012 容错语义；按声明下标精确标记重复项）。
        val verdicts = suite.cases.mapIndexed { index, case ->
            val reason = if (index in validation.invalidCaseIndexes) {
                validation.caseIssues[case.caseId]
            } else {
                null
            }
            CaseVerdict(case = case, invalidReason = reason)
        }
        return LoadedAgentTestSuite(
            suite = suite,
            verdicts = verdicts,
            source = source
        )
    }

    companion object {
        /** 兼容便捷工厂：仅内置资产（既有测试与无导入场景使用）。 */
        fun builtInOnly(loader: AgentTestCaseLoader): AgentTestCaseRepository =
            AgentTestCaseRepository(
                builtIn = BuiltInSuiteSource(loader),
                active = null,
                parser = AgentTestSuiteParser(),
                validator = AgentTestSuiteValidator()
            )
    }
}
