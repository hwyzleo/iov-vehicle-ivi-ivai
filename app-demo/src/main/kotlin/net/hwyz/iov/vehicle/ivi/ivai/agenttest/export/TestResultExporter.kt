package net.hwyz.iov.vehicle.ivi.ivai.agenttest.export

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.TestCaseExecutionResult
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.timing.TestCaseTiming

/**
 * 导出的结果文件（IVI-IVAI-DSN-CR-014 导出接口）。
 */
data class ExportedFile(
    val fileName: String,
    val mimeType: String = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    val bytes: ByteArray
)

/**
 * 测试结果导出器（IVI-IVAI-DSN-CR-014 导出接口）。
 */
interface TestResultExporter {
    /**
     * 导出 XLSX。导出前校验：批次非空、全部用例终态、caseId 无重复、必填时间存在、
     * LLM 时间关系合法、参数 JSON 可序列化；校验失败抛 [ExportValidationException]，
     * 不生成部分文件。
     */
    suspend fun exportXlsx(
        batchId: String,
        results: List<TestCaseExecutionResult>
    ): ExportedFile
}

/** 导出校验失败（可读错误，不生成部分文件）。 */
class ExportValidationException(
    val errorCode: String,
    message: String
) : RuntimeException(message)

/**
 * 默认导出器（IVI-IVAI-DSN-CR-014 XLSX 导出契约）。
 *
 * 固定 16 必选列 + 批次 / 状态 / 失败原因 / llmInvoked 辅助列。参数 JSON 使用稳定
 * 键排序；文本单元格防止公式注入；数据来自终态快照，不从页面渲染文本反向解析。
 */
class DefaultTestResultExporter(
    private val writer: XlsxWorkbookWriter = XlsxWorkbookWriter(),
    private val now: () -> LocalDateTime = { LocalDateTime.now() }
) : TestResultExporter {

    override suspend fun exportXlsx(
        batchId: String,
        results: List<TestCaseExecutionResult>
    ): ExportedFile {
        validate(batchId, results)
        val out = ByteArrayOutputStream()
        writer.write(
            out = out,
            sheetName = SHEET_NAME,
            header = HEADER,
            rows = results.map { rowOf(it) }
        )
        val fileName = "IVI-IVAI-Test-Results_${batchId}_${now().format(FILE_TIME_FORMAT)}.xlsx"
        return ExportedFile(
            fileName = fileName,
            bytes = out.toByteArray()
        )
    }

    private fun validate(batchId: String, results: List<TestCaseExecutionResult>) {
        if (batchId.isBlank()) {
            throw ExportValidationException(TestErrorCode.EXPORT_INVALID, "批次 ID 为空")
        }
        if (results.isEmpty()) {
            throw ExportValidationException(TestErrorCode.EXPORT_EMPTY, "批次为空，无可导出的用例")
        }
        if (results.any { !it.status.isTerminal }) {
            throw ExportValidationException(
                TestErrorCode.EXPORT_NOT_TERMINAL,
                "批次存在未完成用例（${results.count { !it.status.isTerminal }} 条），禁止导出"
            )
        }
        val ids = results.map { it.caseId }
        if (ids.distinct().size != ids.size) {
            throw ExportValidationException(
                TestErrorCode.EXPORT_DUPLICATE_CASE_ID,
                "批次存在重复 caseId，禁止导出"
            )
        }
        results.forEach { validateTiming(it.timing, it.caseId) }
        // 参数 JSON 可序列化（稳定键排序失败即报错）。
        results.forEach { r ->
            try {
                stableJson(r.expected.arguments)
                r.actual?.let { stableJson(it.arguments) }
            } catch (e: Exception) {
                throw ExportValidationException(
                    TestErrorCode.EXPORT_SERIALIZE_FAILED,
                    "用例 ${r.caseId} 参数 JSON 序列化失败：${e.message}"
                )
            }
        }
    }

    /** 一致性校验（IVI-IVAI-DSN-CR-014）。 */
    private fun validateTiming(t: TestCaseTiming, caseId: String) {
        if (t.processingStartLatencyMs < 0) {
            throw ExportValidationException(
                TestErrorCode.EXPORT_INVALID_TIMING,
                "用例 $caseId 开始处理耗时 < 0"
            )
        }
        if (t.totalCaseDurationMs < t.processingStartLatencyMs) {
            throw ExportValidationException(
                TestErrorCode.EXPORT_INVALID_TIMING,
                "用例 $caseId 整个用例耗时小于开始处理耗时"
            )
        }
        if (!t.llmInvoked) {
            if (t.llmFirstTokenLatencyMs != null || t.llmCompleteLatencyMs != null) {
                throw ExportValidationException(
                    TestErrorCode.EXPORT_INVALID_TIMING,
                    "用例 $caseId 未调用 LLM 但 LLM 耗时非空"
                )
            }
            return
        }
        t.llmFirstTokenLatencyMs?.let {
            if (it < 0) {
                throw ExportValidationException(
                    TestErrorCode.EXPORT_INVALID_TIMING,
                    "用例 $caseId LLM 首字耗时 < 0"
                )
            }
        }
        val first = t.llmFirstTokenLatencyMs
        val complete = t.llmCompleteLatencyMs
        if (complete != null && first != null && complete < first) {
            throw ExportValidationException(
                TestErrorCode.EXPORT_INVALID_TIMING,
                "用例 $caseId LLM 完整返回耗时小于首字耗时"
            )
        }
    }

    private fun rowOf(r: TestCaseExecutionResult): List<XlsxCell> {
        val expected = r.expected
        val actual = r.actual
        val actualArgsJson = actual?.let { stableJson(it.arguments) }
        val expectedArgsJson = stableJson(expected.arguments)
        val diag = r.diagnostics
        return listOf(
            XlsxCell.XlsxText(r.caseId),
            XlsxCell.XlsxText(r.input),
            // 层级：期望 → 实际 → 一致标记
            XlsxCell.XlsxText(expected.level ?: ""),
            XlsxCell.XlsxText(actual?.level ?: ""),
            XlsxCell.XlsxText(matchMark(expected.level, actual?.level)),
            // 领域：期望 → 实际 → 一致标记
            XlsxCell.XlsxText(expected.domainId ?: ""),
            XlsxCell.XlsxText(actual?.domainId ?: ""),
            XlsxCell.XlsxText(matchMark(expected.domainId, actual?.domainId)),
            // 能力包：期望 → 实际 → 一致标记
            XlsxCell.XlsxText(expected.capabilityPackId ?: ""),
            XlsxCell.XlsxText(actual?.capabilityPackId ?: ""),
            XlsxCell.XlsxText(matchMark(expected.capabilityPackId, actual?.capabilityPackId)),
            // 工具或工作流：期望 → 实际 → 一致标记
            XlsxCell.XlsxText(expected.targetId ?: ""),
            XlsxCell.XlsxText(actual?.targetId ?: ""),
            XlsxCell.XlsxText(matchMark(expected.targetId, actual?.targetId)),
            // 参数：期望 → 实际 → 一致标记（稳定键 JSON 比较）
            XlsxCell.XlsxText(expectedArgsJson),
            XlsxCell.XlsxText(actualArgsJson ?: ""),
            XlsxCell.XlsxText(matchMark(expectedArgsJson, actualArgsJson)),
            // 耗时
            XlsxCell.XlsxNumber(r.timing.processingStartLatencyMs),
            llmCell(r.timing.llmFirstTokenLatencyMs),
            llmCell(r.timing.llmCompleteLatencyMs),
            XlsxCell.XlsxNumber(r.timing.totalCaseDurationMs),
            // 辅助列
            XlsxCell.XlsxText(r.batchId),
            XlsxCell.XlsxText(r.status.name),
            XlsxCell.XlsxText(r.failureReason ?: ""),
            XlsxCell.XlsxText(r.timing.llmInvoked.toString()),
            // CR-018 诊断可选列：运行时终态 / 失败阶段 / 原因码 / 评分维度 / 差异详情 /
            // Domain 证据 / RAG Top-K / 选中候选及分数（缺失写空单元格）。
            XlsxCell.XlsxText(diag?.runtimeStatus?.name ?: ""),
            XlsxCell.XlsxText(diag?.runtimeTerminalStage?.name ?: ""),
            XlsxCell.XlsxText(diag?.runtimeReasonCode ?: ""),
            XlsxCell.XlsxText(diag?.mismatchDimensions?.sortedBy { it.name }?.joinToString(",") ?: ""),
            XlsxCell.XlsxText(diag?.mismatchDetail ?: ""),
            XlsxCell.XlsxText(diag?.domainEvidence ?: ""),
            XlsxCell.XlsxText(diag?.ragTopK ?: ""),
            XlsxCell.XlsxText(diag?.selectedCandidate ?: "")
        )
    }

    /** 一致标记：期望值与实际值均为空时视为一致（如 REJECT 无目标 / SKIPPED）。 */
    private fun matchMark(expected: String?, actual: String?): String =
        if (expected == actual) "一致" else "不一致"

    /** 未调用或未到达的 LLM 阶段写空单元格（不得写 0；页面显示 N/A）。 */
    private fun llmCell(value: Long?): XlsxCell =
        if (value == null) XlsxCell.XlsxEmpty else XlsxCell.XlsxNumber(value)

    /**
     * 稳定键排序的 JSON 序列化：对象键递归排序，保留嵌套对象、数组、布尔值、
     * 数字、null 与字符串类型语义。
     */
    private fun stableJson(arguments: Map<String, Any?>): String {
        val element = toSortedElement(arguments)
        return json.encodeToString(JsonElement.serializer(), element)
    }

    private fun toSortedElement(value: Any?): JsonElement = when (value) {
        null -> JsonPrimitive(null)
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Map<*, *> -> {
            val sorted = value.entries.sortedBy { it.key.toString() }
            val obj = buildJsonObject {
                sorted.forEach { (k, v) -> put(k.toString(), toSortedElement(v)) }
            }
            obj
        }
        is Iterable<*> -> {
            val arr = kotlinx.serialization.json.buildJsonArray {
                value.forEach { add(toSortedElement(it)) }
            }
            arr
        }
        is Array<*> -> toSortedElement(value.toList())
        else -> JsonPrimitive(value.toString())
    }

    companion object {
        const val SHEET_NAME = "Test Results"

        private val FILE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        private val json = Json { prettyPrint = false }

        /**
         * 列契约（用户确认调整版）：期望 → 实际 → 一致标记 分组排列，16 个核心列
         * 语义不变（用例ID/输入/层级/领域/能力包/工具或工作流/参数/四耗时），
         * 之后追加辅助列（批次ID / 状态 / 失败原因 / llmInvoked）与 CR-018 诊断
         * 可选列（运行时终态 / 失败阶段 / 原因码 / 评分维度 / 差异详情 /
         * Domain 证据 / RAG Top-K / 选中候选及分数）。
         */
        val HEADER: List<String> = listOf(
            "用例ID",
            "输入内容",
            "期望层级",
            "实际层级",
            "层级一致",
            "期望领域",
            "实际领域",
            "领域一致",
            "期望能力包",
            "实际能力包",
            "能力包一致",
            "期望工具或工作流",
            "实际工具或工作流",
            "工具或工作流一致",
            "期望参数",
            "实际参数",
            "参数一致",
            "开始处理耗时(ms)",
            "LLM首字返回耗时(ms)",
            "LLM完整返回耗时(ms)",
            "整个用例耗时(ms)",
            "批次ID",
            "执行状态",
            "失败原因",
            "是否调用LLM",
            // CR-018 诊断可选列。
            "运行时终态",
            "运行时失败阶段",
            "运行时原因码",
            "评分失败维度",
            "评分差异详情",
            "Domain证据",
            "RAG Top-K",
            "选中候选及分数"
        )
    }
}
