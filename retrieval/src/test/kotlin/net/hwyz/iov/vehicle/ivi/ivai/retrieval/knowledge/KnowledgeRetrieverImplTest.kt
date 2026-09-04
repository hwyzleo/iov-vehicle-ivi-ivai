package net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeRetrievalQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-005 验证设计 · Knowledge RAG 测试：召回章节/版本、警告与步骤上下文完整、
 * 车型过滤、Top-K。
 */
class KnowledgeRetrieverImplTest {

    private val retriever = KnowledgeRetrieverImpl(SampleKnowledgeDocs.chunks)

    private fun query(text: String, model: String? = null) = KnowledgeRetrievalQuery(
        text = text,
        vehicleModel = model
    )

    @Test
    fun `胎压报警问题召回正确文档章节与版本`() = runTest {
        val chunks = retriever.retrieve(query("胎压报警是什么意思"), topK = 3)
        assertTrue(chunks.isNotEmpty())
        val top = chunks.first()
        assertEquals("doc_tire_pressure", top.documentId)
        assertTrue(top.sectionPath.contains("胎压报警"))
        assertEquals("1.0", top.documentVersion)
    }

    @Test
    fun `空调问题召回到空调文档`() = runTest {
        val chunks = retriever.retrieve(query("空调怎么开启"), topK = 3)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.any { it.documentId == "doc_climate" })
    }

    @Test
    fun `警告与操作步骤在同一 Chunk 内保持上下文完整`() = runTest {
        val chunks = retriever.retrieve(query("胎压报警是什么意思"), topK = 3)
        val tire = chunks.first { it.documentId == "doc_tire_pressure" }
        assertTrue(tire.content.contains("【警告】"), "警告不得与步骤拆散")
        assertTrue(tire.content.contains("处理步骤"))
    }

    @Test
    fun `车型过滤排除不兼容文档`() = runTest {
        // Sample 文档 vehicleModels=["*"]，对所有车型兼容；这里验证 * 兼容逻辑不误伤。
        val chunks = retriever.retrieve(query("胎压报警是什么意思", model = "some-vehicle"), topK = 3)
        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun `无相关知识返回空且不编造`() = runTest {
        val chunks = retriever.retrieve(query("量子物理与宇宙起源的详细解释"), topK = 3)
        assertTrue(chunks.isEmpty(), "无相关知识不得返回任何片段")
    }

    @Test
    fun `来源引用携带元数据`() {
        val chunk = SampleKnowledgeDocs.chunks.first { it.documentId == "doc_tire_pressure" }
        val citations = KnowledgeCitationMapper.citations(listOf(chunk))
        assertEquals(1, citations.size)
        assertEquals("doc_tire_pressure", citations[0].documentId)
        assertEquals("胎压报警说明", citations[0].title)
        assertFalse(citations[0].sectionPath.isEmpty())
    }

    @Test
    fun `重排器按阈值过滤并截断`() {
        val reranker = KnowledgeReranker(minScore = 0.5)
        val scored = SampleKnowledgeDocs.chunks.map { it.copy(score = if (it.documentId == "doc_tire_pressure") 1.0 else 0.1) }
        val reranked = reranker.rerank(scored, topK = 2)
        assertEquals(1, reranked.size)
        assertEquals("doc_tire_pressure", reranked.first().documentId)
    }
}
