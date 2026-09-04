package net.hwyz.iov.vehicle.ivi.ivai.retrieval.tool

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolCandidate
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetrievalQuery
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.ToolRetriever
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-005 验证设计 · Tool RAG 测试：固定/混合规则检索遵循同一接口、隐式冷表达
 * 召回升温工具、车型/版本过滤、Top-K 与空结果行为。
 */
class ToolRetrieverTest {

    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
    private val fixed = FixedToolRetriever(registry)
    private val hybrid = HybridRuleToolRetriever(registry)

    private fun query(text: String, version: String? = null, model: String? = null) = ToolRetrievalQuery(
        text = text,
        vehicleModel = model,
        softwareVersion = version
    )

    @Test
    fun `固定候选与混合规则遵循同一 ToolRetriever 接口`() = runTest {
        val both: List<ToolRetriever> = listOf(fixed, hybrid)
        for (retriever in both) {
            val candidates = retriever.retrieve(query("打开空调"), topK = 6)
            assertTrue(candidates.all { it is ToolCandidate })
            assertTrue(candidates.all { it.toolId.isNotBlank() })
        }
    }

    @Test
    fun `我有点冷召回升温相关工具且不直接选开空调`() = runTest {
        val candidates = hybrid.retrieve(query("我有点冷"), topK = 5)
        assertTrue(candidates.isNotEmpty(), "冷表达应召回工具")
        val top = candidates.first()
        assertEquals("climate.temperature_increase", top.toolId)
        assertTrue(candidates.none { it.toolId == "climate.power_on" })
    }

    @Test
    fun `太热了召回升温降温相关且不含 power_on`() = runTest {
        val candidates = hybrid.retrieve(query("太热了"), topK = 5)
        assertTrue(candidates.isNotEmpty())
        val ids = candidates.map { it.toolId }
        assertTrue("climate.temperature_decrease" in ids)
        assertFalse("climate.power_on" in ids)
    }

    @Test
    fun `车型与软件版本过滤结果正确`() = runTest {
        // 气候工具 softwareRange = >=0.1.0 → 0.0.1 应被过滤为空（对 hybrid 同样）。
        val empty = hybrid.retrieve(query("打开空调", version = "0.0.1"), topK = 5)
        assertTrue(empty.isEmpty(), "版本不匹配应无候选")

        val ok = hybrid.retrieve(query("打开空调", version = "0.1.0"), topK = 5)
        assertTrue(ok.isNotEmpty())
    }

    @Test
    fun `固定候选按 selectionPriority 排序并截断 Top-K`() = runTest {
        val all = fixed.retrieve(query("任意"), topK = 10)
        assertEquals(6, all.size)
        // selectionPriority 高者优先：temperature_increase(30) > temperature_set(20) > power_on(10) > status(5)
        val priorityOrder = all.map { it.toolId }
        assertTrue(priorityOrder.indexOf("climate.temperature_increase") < priorityOrder.indexOf("climate.power_on"))
        val truncated = fixed.retrieve(query("任意"), topK = 3)
        assertEquals(3, truncated.size)
    }

    @Test
    fun `Top-K 截断与低分过滤不返回集合外结果`() = runTest {
        val candidates = hybrid.retrieve(query("我有点冷"), topK = 1)
        assertEquals(1, candidates.size)
        assertTrue(candidates.all { it.toolId == "climate.temperature_increase" })
    }
}
