package net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository

import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
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
 * 加载结果（IVI-IVAI-DSN-CR-012 校验规则）：
 *  - Suite 整体无法解析 / schemaVersion 不支持 / suiteId 为空 → [errorCode] 置
 *    IVAI-TEST-001，页面显示整体错误且不允许开始。
 *  - 单条非法用例（重复 caseId / 字段缺失 / ID 格式非法）→ 对应 [CaseVerdict] 标记
 *    INVALID 并跳过，不阻止其他合法用例加载。
 */
data class AgentTestLoadResult(
    val suite: AgentTestSuite?,
    val verdicts: List<CaseVerdict> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    /** 可执行的合法用例（声明顺序保持；disabled 用例仍合法，仅在运行时跳过）。 */
    val validCases: List<AgentTestCase>
        get() = verdicts.filter { it.isValid }.map { it.case }

    /** caseId → 非法原因（供 UI 标记；重复 id 折叠为单条）。 */
    val invalidCases: Map<String, String>
        get() = verdicts.filter { !it.isValid }.associate { it.case.caseId to it.invalidReason!! }

    val hasFatalError: Boolean get() = errorCode != null
}

/**
 * 本地测试资产仓库（IVI-IVAI-DSN-CR-012）。
 *
 * 解析并校验版本化 JSON Suite。首期不依赖服务端 / 数据库 / 网络下发。
 */
class AgentTestCaseRepository(
    private val loader: AgentTestCaseLoader,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    fun load(): AgentTestLoadResult {
        val raw = loader.load()
        if (raw.isNullOrBlank()) {
            return AgentTestLoadResult(
                suite = null,
                errorCode = TestErrorCode.SUITE_INVALID,
                errorMessage = "测试资产缺失或为空（Release 构建不打包 debug assets）"
            )
        }
        val suite = try {
            json.decodeFromString<AgentTestSuite>(raw)
        } catch (e: Exception) {
            return AgentTestLoadResult(
                suite = null,
                errorCode = TestErrorCode.SUITE_INVALID,
                errorMessage = "Suite JSON 解析失败：${e.message}"
            )
        }
        if (suite.schemaVersion < 1 || suite.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            return AgentTestLoadResult(
                suite = null,
                errorCode = TestErrorCode.SUITE_INVALID,
                errorMessage = "不支持的 schemaVersion=${suite.schemaVersion}（支持 1..$SUPPORTED_SCHEMA_VERSION）"
            )
        }
        if (suite.suiteId.isBlank()) {
            return AgentTestLoadResult(
                suite = null,
                errorCode = TestErrorCode.SUITE_INVALID,
                errorMessage = "suiteId 为空"
            )
        }

        val verdicts = mutableListOf<CaseVerdict>()
        val seenCaseIds = HashSet<String>()
        for (case in suite.cases) {
            // 逐条判定：重复 caseId 只标记后出现的为 INVALID，首条保持合法。
            val reason = validateCase(case, seenCaseIds)
            verdicts += CaseVerdict(case = case, invalidReason = reason)
        }
        return AgentTestLoadResult(suite = suite, verdicts = verdicts)
    }

    /** 返回 null 表示合法；否则返回该用例被标记 INVALID 的原因（IVAI-TEST-002）。 */
    private fun validateCase(case: AgentTestCase, seenCaseIds: MutableSet<String>): String? {
        if (!seenCaseIds.add(case.caseId)) return "caseId 重复：${case.caseId}"
        if (case.caseId.isBlank()) return "caseId 为空"
        if (case.input.isBlank()) return "input 为空"
        if (case.expectedCapabilityPack.isBlank()) return "expectedCapabilityPack 为空"
        val target = case.expectedTarget ?: return null
        if (!TARGET_ID_PATTERN.matches(target.id)) {
            return "目标 ID 格式非法：${target.id}（仅允许字母、数字、点、短横线、下划线）"
        }
        return null
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        private val TARGET_ID_PATTERN = Regex("[A-Za-z0-9._-]+")
    }
}
