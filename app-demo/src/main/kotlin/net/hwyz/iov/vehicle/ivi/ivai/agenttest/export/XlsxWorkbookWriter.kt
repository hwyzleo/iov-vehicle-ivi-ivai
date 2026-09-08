package net.hwyz.iov.vehicle.ivi.ivai.agenttest.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 单元格值（IVI-IVAI-DSN-CR-014 XLSX 导出契约）。
 *
 * - [XlsxNumber]：数值单元格（可排序 / 求平均 / 透视）。
 * - [XlsxText]：文本单元格（输入内容 / JSON 参数；防止公式注入）。
 * - [XlsxEmpty]：空单元格（未调用 / 未到达的 LLM 阶段）。
 */
sealed interface XlsxCell {
    data class XlsxNumber(val value: Long) : XlsxCell
    data class XlsxText(val value: String) : XlsxCell
    data object XlsxEmpty : XlsxCell
}

/**
 * 最小 Office Open XML（XLSX）工作簿生成器（IVI-IVAI-DSN-CR-014）。
 *
 * 生成真正的 .xlsx（zip 容器 + XML），不是 CSV 改扩展名。特性：
 * - 首行冻结（freeze pane）并启用自动筛选（autoFilter）。
 * - 数值列写数值单元格；文本写内联字符串（inlineStr）。
 * - 文本单元格以 `=`、`+`、`-`、`@` 开头时按文本处理，防止公式注入。
 * - 逐行写入 sheetData，支持大批量（1,174 条及以上）流式生成。
 *
 * 纯 JVM 实现，不依赖 Android / POI；可在单测中直接解压校验。
 */
class XlsxWorkbookWriter {

    /**
     * 生成工作簿并写入 [out]。不会关闭 [out]。
     *
     * @param sheetName 工作表名（固定建议为 "Test Results"）。
     * @param header 表头（首行）。
     * @param rows 数据行（逐行消费，不整体缓存）。
     */
    fun write(
        out: OutputStream,
        sheetName: String,
        header: List<String>,
        rows: Iterable<List<XlsxCell>>
    ) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(ContentTypes)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(Rels)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(workbookXml(sheetName))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(WorkbookRels)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/styles.xml"))
            zip.write(Styles)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            writeSheet(zip, header, rows)
            zip.closeEntry()
        }
    }

    private fun writeSheet(
        zip: ZipOutputStream,
        header: List<String>,
        rows: Iterable<List<XlsxCell>>
    ) {
        val colCount = header.size
        val lastCol = columnName(colCount)
        zip.write(
            (
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                    """<sheetViews><sheetView workbookViewId="0">""" +
                    """<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>""" +
                    """</sheetView></sheetViews>""" +
                    """<sheetFormatPr defaultRowHeight="15"/>""" +
                    """<cols><col min="1" max="$colCount" width="22" customWidth="1"/></cols>""" +
                    """<sheetData>"""
                ).toByteArray(Charsets.UTF_8)
            )

        // 表头行（index 1）加粗样式（style 1）。
        writeRow(zip, 1, header.map { XlsxCell.XlsxText(it) }, headerStyle = true)

        var rowIndex = 2
        for (row in rows) {
            writeRow(zip, rowIndex, row, headerStyle = false)
            rowIndex++
        }

        zip.write(("</sheetData>" +
            """<autoFilter ref="A1:${lastCol}${rowIndex - 1}"/>""" +
            """</worksheet>""").toByteArray(Charsets.UTF_8))
    }

    private fun writeRow(
        zip: ZipOutputStream,
        rowIndex: Int,
        cells: List<XlsxCell>,
        headerStyle: Boolean
    ) {
        val sb = StringBuilder()
        sb.append("""<row r="$rowIndex">""")
        cells.forEachIndexed { i, cell ->
            val ref = "${columnName(i + 1)}$rowIndex"
            when (cell) {
                is XlsxCell.XlsxNumber ->
                    sb.append("""<c r="$ref"${if (headerStyle) " s=\"1\"" else ""}><v>${cell.value}</v></c>""")
                is XlsxCell.XlsxText ->
                    sb.append(
                        """<c r="$ref"${if (headerStyle) " s=\"1\"" else ""} t="inlineStr">""" +
                            """<is><t xml:space="preserve">${escapeXml(cell.value)}</t></is></c>"""
                    )
                is XlsxCell.XlsxEmpty -> sb.append("""<c r="$ref"/>""")
            }
        }
        sb.append("</row>")
        zip.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun workbookXml(sheetName: String): ByteArray = (
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<sheets><sheet name="${escapeXml(sheetName)}" sheetId="1" """ +
            """r:id="rId1"/></sheets></workbook>"""
        ).toByteArray(Charsets.UTF_8)

    private fun escapeXml(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                // 文本节点内引号无需转义，保持 JSON / 中文可读；属性值由内部常量生成。
                else -> append(c)
            }
        }
    }

    private fun columnName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append(('A' + rem))
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    companion object {
        private val ContentTypes = (
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                """<Default Extension="xml" ContentType="application/xml"/>""" +
                """<Override PartName="/xl/workbook.xml" """ +
                """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                """<Override PartName="/xl/worksheets/sheet1.xml" """ +
                """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
                """<Override PartName="/xl/styles.xml" """ +
                """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                """</Types>"""
            ).toByteArray(Charsets.UTF_8)

        private val Rels = (
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" """ +
                """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" """ +
                """Target="xl/workbook.xml"/></Relationships>"""
            ).toByteArray(Charsets.UTF_8)

        private val WorkbookRels = (
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" """ +
                """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" """ +
                """Target="worksheets/sheet1.xml"/>""" +
                """<Relationship Id="rId2" """ +
                """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" """ +
                """Target="styles.xml"/></Relationships>"""
            ).toByteArray(Charsets.UTF_8)

        /** 冻结表头用普通样式（style 0）+ 加粗（style 1）。 */
        private val Styles = (
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>""" +
                """<font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
                """<fills count="1"><fill><patternFill patternType="none"/></fill></fills>""" +
                """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
                """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
                """<cellXfs count="2">""" +
                """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
                """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
                """</cellXfs></styleSheet>"""
            ).toByteArray(Charsets.UTF_8)
    }
}
