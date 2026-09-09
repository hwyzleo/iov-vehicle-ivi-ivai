package net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository

import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestSuite

/**
 * Suite 来源（IVI-IVAI-DSN-CR-015）：内置资产 / 导入激活文件。
 */
enum class SuiteSourceType {
    BUILT_IN,
    ACTIVE_FILE
}

/**
 * 不可变加载结果（IVI-IVAI-DSN-CR-012 / CR-015）。
 *
 *  - Suite 整体无法解析 / schemaVersion 不支持 / suiteId 为空 → [errorCode] 置
 *    IVAI-TEST-001（内置路径）或 IVAI-TEST-IMPORT-008（激活路径），页面显示整体
 *    错误且不允许开始。
 *  - 单条非法用例（重复 caseId / 字段缺失 / ID 格式非法）→ 对应 [CaseVerdict] 标记
 *    INVALID 并跳过，不阻止其他合法用例加载（CR-012 容错语义；导入路径整包拒绝，
 *    不产生本结果）。
 */
data class LoadedAgentTestSuite(
    val suite: AgentTestSuite?,
    val verdicts: List<CaseVerdict> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val source: SuiteSourceType = SuiteSourceType.BUILT_IN,
    /** 激活文件内容 SHA-256（内置资产为 null）。 */
    val sha256: String? = null,
    /** 激活 Suite 缺失 / 损坏后回退内置的可读原因（IMPORT-008）。 */
    val degradedReason: String? = null
) {
    /** 可执行的合法用例（声明顺序保持；disabled 用例仍合法，仅在运行时跳过）。 */
    val validCases: List<AgentTestCase>
        get() = verdicts.filter { it.isValid }.map { it.case }

    /** caseId → 非法原因（供 UI 标记；重复 id 折叠为单条）。 */
    val invalidCases: Map<String, String>
        get() = verdicts.filter { !it.isValid }.associate { it.case.caseId to it.invalidReason!! }

    val hasFatalError: Boolean get() = errorCode != null

    /** Suite 声明用例总数。 */
    val caseCount: Int get() = suite?.cases?.size ?: 0
}
