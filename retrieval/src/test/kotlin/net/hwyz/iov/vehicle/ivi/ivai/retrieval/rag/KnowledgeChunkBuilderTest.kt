package net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeSourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-011 验证设计 · KnowledgeChunkBuilder：标题层级/章节位置保留、警告与步骤
 * 同 Chunk、contentHash 确定性、车型/版本/语言继承来源。
 */
class KnowledgeChunkBuilderTest {

    @Test
    fun `按章节切分并保留标题层级与章节位置`() {
        val source = KnowledgeSource(
            sourceId = "doc_tire_pressure",
            sourceType = KnowledgeSourceType.FAULT_HELP,
            title = "胎压报警说明",
            sourceVersion = "1.0",
            sections = listOf(
                KnowledgeSection(
                    sectionId = "s1",
                    title = "胎压报警",
                    sectionPath = "故障/胎压报警",
                    content = "胎压报警说明：当轮胎气压过低时仪表盘点亮报警灯。\n【警告】请勿高速行驶。\n处理步骤：1. 安全停车检查；2. 补气。"
                ),
                KnowledgeSection(
                    sectionId = "s2",
                    title = "胎压监测设置",
                    sectionPath = "故障/胎压监测设置",
                    content = "可在中控屏设置胎压监测阈值。"
                )
            )
        )
        val chunks = KnowledgeChunkBuilder().build(source)
        assertEquals(2, chunks.size)
        val first = chunks.first()
        assertEquals("doc_tire_pressure.s1", first.chunkId)
        assertEquals("doc_tire_pressure", first.sourceId)
        assertEquals("故障/胎压报警", first.sectionPath)
        assertEquals(KnowledgeSourceType.FAULT_HELP, first.sourceType)
        // 警告与操作步骤在同一 Chunk（不拆散安全上下文）。
        assertTrue(first.content.contains("【警告】"))
        assertTrue(first.content.contains("处理步骤"))
        assertTrue(first.contentHash.isNotBlank())
    }

    @Test
    fun `contentHash 确定性且来源属性继承`() {
        val source = KnowledgeSource(
            sourceId = "doc_climate",
            sourceType = KnowledgeSourceType.FEATURE_EXPLANATION,
            title = "空调使用",
            sourceVersion = "2.1",
            vehicleModels = setOf("model-x"),
            language = "zh-CN",
            sections = listOf(
                KnowledgeSection("s1", "开启空调", "空调/使用", "温度 16 到 32 度。")
            )
        )
        val builder = KnowledgeChunkBuilder()
        val chunks = builder.build(source)
        val again = builder.build(source)
        assertEquals(chunks.single().contentHash, again.single().contentHash)
        assertEquals(setOf("model-x"), chunks.single().vehicleModels)
        assertEquals("zh-CN", chunks.single().language)
        assertEquals("2.1", chunks.single().sourceVersion)
        assertEquals(null, chunks.single().softwareVersions.min) // 未指定则无版本约束
    }
}
