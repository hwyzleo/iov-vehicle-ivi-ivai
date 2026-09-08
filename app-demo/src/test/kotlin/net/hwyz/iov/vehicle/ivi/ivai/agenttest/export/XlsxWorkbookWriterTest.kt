package net.hwyz.iov.vehicle.ivi.ivai.agenttest.export

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-014 XLSX 生成器单测：真实 OOXML zip 结构、冻结首行、自动筛选、数值单元格、
 * 公式注入防护、中文/换行/特殊字符转义、大批量行数。
 */
class XlsxWorkbookWriterTest {

    private fun writeWorkbook(
        header: List<String> = listOf("A", "B", "C"),
        rows: List<List<XlsxCell>> = listOf(
            listOf(XlsxCell.XlsxText("=SUM(1,2)"), XlsxCell.XlsxNumber(1), XlsxCell.XlsxEmpty),
            listOf(XlsxCell.XlsxText("中文+换行\n第二行&<>\"'"), XlsxCell.XlsxNumber(2), XlsxCell.XlsxText("-123"))
        )
    ): ByteArray {
        val out = ByteArrayOutputStream()
        XlsxWorkbookWriter().write(out, "Test Results", header, rows)
        return out.toByteArray()
    }

    /** 解压所有 zip 条目，返回 (name → content)。 */
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val content = zip.readBytes().toString(Charsets.UTF_8)
                result[entry.name] = content
                zip.closeEntry()
            }
        }
        return result
    }

    @Test
    fun `生成真正的 OOXML zip 结构`() {
        val parts = unzip(writeWorkbook())
        assertTrue(parts.containsKey("[Content_Types].xml"))
        assertTrue(parts.containsKey("_rels/.rels"))
        assertTrue(parts.containsKey("xl/workbook.xml"))
        assertTrue(parts.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue(parts.containsKey("xl/styles.xml"))
        // 工作表名固定。
        assertTrue(parts["xl/workbook.xml"]!!.contains("Test Results"))
    }

    @Test
    fun `首行冻结且启用自动筛选`() {
        val sheet = unzip(writeWorkbook())["xl/worksheets/sheet1.xml"]!!
        assertTrue(sheet.contains("""<pane ySplit="1""""))
        assertTrue(sheet.contains("""state="frozen""""))
        assertTrue(sheet.contains("""<autoFilter ref="A1:C3"/>"""))
    }

    @Test
    fun `数值写入数值单元格 空写入空单元格`() {
        val sheet = unzip(writeWorkbook())["xl/worksheets/sheet1.xml"]!!
        assertTrue(sheet.contains("""<c r="B2"><v>1</v></c>"""))
        assertTrue(sheet.contains("""<c r="B3"><v>2</v></c>"""))
        assertTrue(sheet.contains("""<c r="C2"/>"""))
        // 数值列不写 t 属性（默认数字）。
        assertFalse(sheet.contains("""t="inlineStr"><v>"""))
    }

    @Test
    fun `公式注入按文本处理`() {
        val sheet = unzip(writeWorkbook())["xl/worksheets/sheet1.xml"]!!
        // = 开头的输入内容写入 inlineStr 文本单元格（Excel 不执行公式）。
        assertTrue(sheet.contains("""t="inlineStr""""))
        assertTrue(sheet.contains("=SUM(1,2)"))
        // - 开头同样按文本处理。
        assertTrue(sheet.contains("-123"))
    }

    @Test
    fun `中文 换行 与 XML 特殊字符安全转义`() {
        val sheet = unzip(writeWorkbook())["xl/worksheets/sheet1.xml"]!!
        assertTrue(sheet.contains("中文"))
        // 换行原样保留在文本中。
        assertTrue(sheet.contains("中文+换行\n第二行"))
        // XML 特殊字符已转义（文本节点内引号无需转义）。
        assertTrue(sheet.contains("&amp;"))
        assertTrue(sheet.contains("&lt;"))
        assertTrue(sheet.contains("&gt;"))
        assertTrue(sheet.contains("\"'"))
    }

    @Test
    fun `表头使用加粗样式 数据行普通样式`() {
        val sheet = unzip(writeWorkbook())["xl/worksheets/sheet1.xml"]!!
        // 表头（row 1）单元格带 s="1"（加粗）。
        assertTrue(sheet.contains("""<c r="A1" s="1""""))
        // 数据行不带样式属性。
        assertFalse(sheet.contains("""<c r="A2" s="1""""))
    }

    @Test
    fun `大批量 1174 行可流式生成且行数正确`() {
        val header = listOf("用例ID", "耗时")
        val rows = (1..1174).map { i ->
            listOf(XlsxCell.XlsxText("CASE-$i"), XlsxCell.XlsxNumber(i.toLong()))
        }
        val out = ByteArrayOutputStream()
        XlsxWorkbookWriter().write(out, "Test Results", header, rows)
        val sheet = unzip(out.toByteArray())["xl/worksheets/sheet1.xml"]!!
        // 表头 + 1174 行 → autoFilter 到第 1175 行。
        assertTrue(sheet.contains("""<autoFilter ref="A1:B1175"/>"""))
        // 首行与末行均存在。
        assertTrue(sheet.contains("""<c r="A2" t="inlineStr"><is><t xml:space="preserve">CASE-1</t></is></c>"""))
        assertTrue(sheet.contains("""<c r="B1175"><v>1174</v></c>"""))
    }

    @Test
    fun `多列定位列字母正确`() {
        val header = (1..16).map { "col$it" }
        val rows = listOf(header.map { XlsxCell.XlsxText("v") })
        val sheet = unzip(writeWorkbook(header, rows))["xl/worksheets/sheet1.xml"]!!
        assertTrue(sheet.contains("""<c r="A1""""))
        assertTrue(sheet.contains("""<c r="P1""""))
        assertTrue(sheet.contains("""<autoFilter ref="A1:P2"/>"""))
    }
}
