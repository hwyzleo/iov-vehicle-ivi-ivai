package net.hwyz.iov.vehicle.ivi.ivai.agenttest.import

import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestSuite
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ExpectedOutcome

/**
 * Suite 全量校验结果（IVI-IVAI-DSN-CR-015）。
 *
 *  - [fatalErrorCode] / [fatalErrorMessage]：整包致命项（版本 / suiteId / cases 空 /
 *    资源边界超限），存在时阻止整包加载。
 *  - [caseIssues]：单条非法（重复 caseId / 空 input / 空 pack / 目标 ID 格式非法）。
 *
 * 导入路径：fatalError 或任意 caseIssue 均整包拒绝（IVAI-REQ-146）；
 * 内置路径：仅 fatalError 阻止加载，caseIssue 按 CR-012 容错跳过并显示 INVALID。
 */
data class SuiteValidationResult(
    val valid: Boolean,
    val fatalErrorCode: String? = null,
    val fatalErrorMessage: String? = null,
    val caseIssues: Map<String, String> = emptyMap(),
    /** 声明顺序中标记非法的用例下标（内置容错路径据此精确标记重复发生项）。 */
    val invalidCaseIndexes: Set<Int> = emptySet()
)

/**
 * 测试 Suite 校验器（IVI-IVAI-DSN-CR-015）。与内置加载共用同一字段规则。
 *
 * 规则（IVAI-REQ-145）：
 *  1. suiteId 非空。
 *  1. schemaVersion 属于当前支持集合（失败 → IVAI-TEST-IMPORT-004）。
 *  1. cases 非空且不超过安全上限。
 *  1. caseId 非空并在 Suite 内唯一；顺序按 JSON 数组原顺序保留。
 *  1. input 非空；Tier / Domain / Target Type 等枚举在解析期已校验。
 *  1. expectedCapabilityPack、Target ID 和参数对象符合既有 Suite 规则。
 *  1. description / tags 等可选字段缺失不拒绝合法旧文件。
 */
class AgentTestSuiteValidator(
    private val guard: ImportSizeGuard = ImportSizeGuard(),
    private val maxCaseCount: Int = DEFAULT_MAX_CASE_COUNT
) {

    fun validate(suite: AgentTestSuite): SuiteValidationResult {
        if (suite.suiteId.isBlank()) {
            return failure(TestErrorCode.IMPORT_VALIDATION_FAILED, "suiteId 为空")
        }
        if (suite.schemaVersion < 1 ||
            suite.schemaVersion > AgentTestSuite.SUPPORTED_SCHEMA_VERSION
        ) {
            return failure(
                TestErrorCode.IMPORT_UNSUPPORTED_VERSION,
                "不支持的 schemaVersion=${suite.schemaVersion}（支持 1..${AgentTestSuite.SUPPORTED_SCHEMA_VERSION}）"
            )
        }
        if (suite.cases.isEmpty()) {
            return failure(TestErrorCode.IMPORT_VALIDATION_FAILED, "cases 为空")
        }
        if (suite.cases.size > maxCaseCount) {
            return failure(
                TestErrorCode.IMPORT_VALIDATION_FAILED,
                "cases 数量 ${suite.cases.size} 超过安全上限 $maxCaseCount"
            )
        }
        // 资源边界：字符串长度 / JSON 嵌套深度（超限按校验失败处理）。
        try {
            suite.checkResourceLimits(guard)
        } catch (e: SuiteImportException) {
            return failure(e.errorCode, e.message ?: "资源边界超限")
        }

        // 逐条校验（与 CR-012 相同规则；重复 caseId 只标记后出现的非法）。
        val issues = LinkedHashMap<String, String>()
        val invalidCaseIndexes = LinkedHashSet<Int>()
        val seenCaseIds = HashSet<String>()
        suite.cases.forEachIndexed { index, case ->
            validateCase(case, seenCaseIds)?.let { reason ->
                issues[case.caseId] = reason
                invalidCaseIndexes += index
            }
        }
        return SuiteValidationResult(
            valid = issues.isEmpty(),
            caseIssues = issues,
            invalidCaseIndexes = invalidCaseIndexes
        )
    }

    /** 返回 null 表示合法；否则返回该用例被标记 INVALID 的原因。 */
    private fun validateCase(case: AgentTestCase, seenCaseIds: MutableSet<String>): String? {
        if (!seenCaseIds.add(case.caseId)) return "caseId 重复：${case.caseId}"
        if (case.caseId.isBlank()) return "caseId 为空"
        if (case.input.isBlank()) return "input 为空"
        if (case.expectedCapabilityPack.isBlank()) return "expectedCapabilityPack 为空"
        // CR-019：V2 业务 Outcome 契约——EXECUTE 必须有 Target 与参数断言；
        // NEED_DIALOGUE/REJECTED 必须无可执行 Target（reasonCode 断言可选）。
        val outcome = case.expectedOutcome
        if (outcome != null) {
            when (outcome) {
                ExpectedOutcome.EXECUTE -> {
                    if (case.expectedTarget == null) {
                        return "V2 EXECUTE 用例必须有 expectedTarget（业务目标）"
                    }
                }
                ExpectedOutcome.NEED_DIALOGUE,
                ExpectedOutcome.REJECTED -> {
                    if (case.expectedTarget != null) {
                        return "V2 ${outcome.name} 用例必须无可执行 expectedTarget"
                    }
                }
            }
        }
        val target = case.expectedTarget ?: return null
        if (!TARGET_ID_PATTERN.matches(target.id)) {
            return "目标 ID 格式非法：${target.id}（仅允许字母、数字、点、短横线、下划线）"
        }
        return null
    }

    private fun failure(code: String, message: String) = SuiteValidationResult(
        valid = false,
        fatalErrorCode = code,
        fatalErrorMessage = message
    )

    companion object {
        private val TARGET_ID_PATTERN = Regex("[A-Za-z0-9._-]+")

        /** 单 Suite 用例数量安全上限（覆盖 1,174 / 1,200 契约测试规模并留余量）。 */
        const val DEFAULT_MAX_CASE_COUNT = 10_000
    }
}
