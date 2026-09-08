package net.hwyz.iov.vehicle.ivi.ivai.agenttest.export

import java.time.LocalDateTime
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.IntentActualResult
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.IntentExpectation
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.TestCaseExecutionResult
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.result.TestCaseStatus
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.timing.TestCaseTiming

/**
 * CR-014 导出器单测：16 必选列契约、混合 LLM/N/A 区分、未完成批次拒绝、校验失败
 * 不生成部分文件、caseId 唯一、1,174 条规模、参数 JSON 稳定排序。
 */
class TestResultExporterTest {

    private fun result(
        caseId: String,
        status: TestCaseStatus = TestCaseStatus.PASSED,
        llmInvoked: Boolean = false,
        first: Long? = null,
        complete: Long? = null,
        expected: IntentExpectation = IntentExpectation(
            level = "L0_DETERMINISTIC_TOOL",
            domainId = "CABIN_COMFORT",
            capabilityPackId = "cabin.climate",
            targetId = "climate.power.set",
            arguments = mapOf("enabled" to true, "zone" to "driver")
        ),
        actual: IntentActualResult? = IntentActualResult(
            level = "L0_DETERMINISTIC_TOOL",
            domainId = "CABIN_COMFORT",
            capabilityPackId = "cabin.climate",
            targetId = "climate.power.set",
            arguments = mapOf("zone" to "driver", "enabled" to true)
        )
    ) = TestCaseExecutionResult(
        batchId = "B-1",
        caseId = caseId,
        input = "打开空调",
        expected = expected,
        actual = actual,
        status = status,
        timing = TestCaseTiming(
            processingStartLatencyMs = 10,
            llmFirstTokenLatencyMs = if (llmInvoked) first else null,
            llmCompleteLatencyMs = if (llmInvoked) complete else null,
            totalCaseDurationMs = 100,
            llmInvoked = llmInvoked
        )
    )

    private fun exporter(now: LocalDateTime = LocalDateTime.of(2026, 9, 8, 1, 30, 15)) =
        DefaultTestResultExporter(now = { now })

    private fun unzipSheet(bytes: ByteArray): String {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "xl/worksheets/sheet1.xml") {
                    val content = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry()
                    return content
                }
                zip.closeEntry()
            }
        }
        error("sheet1.xml 不存在")
    }

    private suspend fun expectExportError(
        expectedCode: String,
        rows: List<TestCaseExecutionResult>
    ) {
        val e = runCatching { exporter().exportXlsx("run-1", rows) }.exceptionOrNull()
        assertNotNull(e, "应抛出导出异常")
        assertTrue(e is ExportValidationException, "异常类型应为 ExportValidationException：${e}")
        assertEquals(expectedCode, (e as ExportValidationException).errorCode)
    }

    @Test
    fun `21 核心列按 期望-实际-一致标记 分组且顺序固定 辅助列随后`() {
        val header = DefaultTestResultExporter.HEADER
        // 21 核心列（用例ID/输入 + 5 组期望/实际/一致 + 4 耗时）+ 4 辅助列。
        assertEquals(25, header.size)
        assertEquals(
            listOf(
                "用例ID", "输入内容",
                "期望层级", "实际层级", "层级一致",
                "期望领域", "实际领域", "领域一致",
                "期望能力包", "实际能力包", "能力包一致",
                "期望工具或工作流", "实际工具或工作流", "工具或工作流一致",
                "期望参数", "实际参数", "参数一致",
                "开始处理耗时(ms)", "LLM首字返回耗时(ms)", "LLM完整返回耗时(ms)", "整个用例耗时(ms)"
            ),
            header.subList(0, 21)
        )
        // 辅助列：批次ID / 执行状态 / 失败原因 / 是否调用LLM。
        assertEquals("批次ID", header[21])
        assertEquals("执行状态", header[22])
        assertEquals("失败原因", header[23])
        assertEquals("是否调用LLM", header[24])
    }

    @Test
    fun `文件名与工作表名符合契约`() = runTest {
        val file = exporter().exportXlsx("run-123", listOf(result("C-1")))
        assertEquals("IVI-IVAI-Test-Results_run-123_20260908_013015.xlsx", file.fileName)
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            file.mimeType
        )
        assertTrue(file.bytes.isNotEmpty())
        val wb = ZipInputStream(file.bytes.inputStream()).use { zip ->
            val map = mutableMapOf<String, String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                map[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
            map
        }
        assertTrue(wb["xl/workbook.xml"]!!.contains("Test Results"))
    }

    @Test
    fun `混合 LLM 与无 LLM 用例 耗时列区分空值与数值`() = runTest {
        val rows = listOf(
            result("C-L0", llmInvoked = false),
            result("C-LLM", llmInvoked = true, first = 30, complete = 80)
        )
        val file = exporter().exportXlsx("run-1", rows)
        val sheet = unzipSheet(file.bytes)
        // L0 行：LLM 列写空单元格（S/T 第 2 行）。
        assertTrue(sheet.contains("""<c r="S2"/>"""))
        assertTrue(sheet.contains("""<c r="T2"/>"""))
        // LLM 行：LLM 列写数值（第 3 行）。
        assertTrue(sheet.contains("""<c r="S3"><v>30</v></c>"""))
        assertTrue(sheet.contains("""<c r="T3"><v>80</v></c>"""))
        // 开始处理（R）/ 整个用例耗时（U）写数值。
        assertTrue(sheet.contains("""<c r="R2"><v>10</v></c>"""))
        assertTrue(sheet.contains("""<c r="U2"><v>100</v></c>"""))
        // 一致标记：期望/实际层级、领域、能力包、目标、参数均一致。
        assertTrue(sheet.contains("""<c r="E2" t="inlineStr"><is><t xml:space="preserve">一致</t></is></c>"""))
        assertTrue(sheet.contains("""<c r="H2" t="inlineStr"><is><t xml:space="preserve">一致</t></is></c>"""))
        assertTrue(sheet.contains("""<c r="K2" t="inlineStr"><is><t xml:space="preserve">一致</t></is></c>"""))
        assertTrue(sheet.contains("""<c r="N2" t="inlineStr"><is><t xml:space="preserve">一致</t></is></c>"""))
        assertTrue(sheet.contains("""<c r="Q2" t="inlineStr"><is><t xml:space="preserve">一致</t></is></c>"""))
    }

    @Test
    fun `期望与实际参数稳定键排序 JSON`() = runTest {
        val file = exporter().exportXlsx("run-1", listOf(result("C-1")))
        val sheet = unzipSheet(file.bytes)
        assertTrue(sheet.contains("""{"enabled":true,"zone":"driver"}"""))
    }

    @Test
    fun `中文 换行 公式前缀输入安全导出`() = runTest {
        val r = TestCaseExecutionResult(
            batchId = "B-1",
            caseId = "C-1",
            input = "=1+1\n中文输入@x",
            expected = IntentExpectation(arguments = mapOf("text" to "=SUM(1,2)", "nl" to "a\nb")),
            actual = IntentActualResult(arguments = mapOf("nl" to "a\nb", "text" to "=SUM(1,2)")),
            status = TestCaseStatus.PASSED,
            timing = TestCaseTiming(10, null, null, 100, false)
        )
        val file = exporter().exportXlsx("run-1", listOf(r))
        val sheet = unzipSheet(file.bytes)
        assertTrue(sheet.contains("=1+1"))
        assertTrue(sheet.contains("中文输入@x"))
        assertTrue(sheet.contains("=SUM(1,2)"))
    }

    @Test
    fun `批次未完成 导出接口拒绝且不生成文件`() = runTest {
        val rows = listOf(
            result("C-1"),
            result("C-2", status = TestCaseStatus.RUNNING)
        )
        expectExportError(TestErrorCode.EXPORT_NOT_TERMINAL, rows)
    }

    @Test
    fun `空批次拒绝导出`() = runTest {
        expectExportError(TestErrorCode.EXPORT_EMPTY, emptyList())
    }

    @Test
    fun `caseId 重复拒绝导出`() = runTest {
        expectExportError(TestErrorCode.EXPORT_DUPLICATE_CASE_ID, listOf(result("C-1"), result("C-1")))
    }

    @Test
    fun `llmInvoked=false 但 LLM 耗时非空 拒绝导出`() = runTest {
        val bad = result("C-1").copy(
            timing = TestCaseTiming(10, 30, 80, 100, llmInvoked = false)
        )
        expectExportError(TestErrorCode.EXPORT_INVALID_TIMING, listOf(bad))
    }

    @Test
    fun `LLM 完整耗时小于首字耗时 拒绝导出`() = runTest {
        val bad = result("C-1").copy(
            timing = TestCaseTiming(10, 80, 30, 100, llmInvoked = true)
        )
        expectExportError(TestErrorCode.EXPORT_INVALID_TIMING, listOf(bad))
    }

    @Test
    fun `整个用例耗时小于开始处理耗时 拒绝导出`() = runTest {
        val bad = result("C-1").copy(
            timing = TestCaseTiming(100, null, null, 50, llmInvoked = false)
        )
        expectExportError(TestErrorCode.EXPORT_INVALID_TIMING, listOf(bad))
    }

    @Test
    fun `全部终态导出 每条用例恰好一行`() = runTest {
        val rows = listOf(
            result("C-PASS"),
            result("C-FAIL", status = TestCaseStatus.FAILED),
            result("C-REJECT", status = TestCaseStatus.REJECTED),
            result("C-TIMEOUT", status = TestCaseStatus.TIMED_OUT, llmInvoked = true, first = 20, complete = null),
            result("C-CANCELLED", status = TestCaseStatus.CANCELLED),
            result("C-ERROR", status = TestCaseStatus.ERROR),
            result("C-SKIPPED", status = TestCaseStatus.SKIPPED)
        )
        val file = exporter().exportXlsx("run-1", rows)
        val sheet = unzipSheet(file.bytes)
        rows.forEach { assertTrue(sheet.contains(it.caseId)) }
        assertTrue(sheet.contains("PASSED"))
        assertTrue(sheet.contains("REJECTED"))
        assertTrue(sheet.contains("SKIPPED"))
        // C-TIMEOUT 在第 5 行（表头后）：LLM 首字有值、完整未到达 → 空单元格。
        assertTrue(sheet.contains("""<c r="S5"><v>20</v></c>"""))
        assertTrue(sheet.contains("""<c r="T5"/>"""))
        // REJECT 用例（期望无目标且实际无目标）→ 工具或工作流一致。
        assertTrue(sheet.contains("""<c r="N4" t="inlineStr"><is><t xml:space="preserve">一致</t></is></c>"""))
    }

    @Test
    fun `1174 条规模导出成功且行数正确`() = runTest {
        val rows = (1..1174).map { i ->
            result(
                caseId = "TC-%04d".format(i),
                status = if (i % 3 == 0) TestCaseStatus.REJECTED else TestCaseStatus.PASSED
            )
        }
        val file = exporter().exportXlsx("run-big", rows)
        val sheet = unzipSheet(file.bytes)
        assertTrue(sheet.contains("""<autoFilter ref="A1:Y1175"/>"""))
        assertTrue(sheet.contains("TC-0001"))
        assertTrue(sheet.contains("TC-1174"))
    }
}
