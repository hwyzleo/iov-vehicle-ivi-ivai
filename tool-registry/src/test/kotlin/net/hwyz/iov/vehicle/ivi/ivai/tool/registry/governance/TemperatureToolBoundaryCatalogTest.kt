package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 温度相似 Tool 边界目录（IVI-IVAI-DSN-CR-019 单元测试）。
 *
 * adjust/set 职责固化、操作语义/数值角色/正反例/冲突集；contentHash 稳定且
 * 内容变化必须反映到 Hash（Catalog/Schema/Alias 变化触发 Embedding 刷新）。
 */
class TemperatureToolBoundaryCatalogTest {

    @Test
    fun `adjust 与 set 均存在且职责固化`() {
        val adjust = TemperatureToolBoundaryCatalog.forTool("climate.temperature.adjust")
        assertNotNull(adjust)
        assertEquals(
            setOf(TemperatureOperationSemantic.RELATIVE_DELTA.name),
            adjust!!.operationSemantics
        )
        assertTrue(adjust.valueRoles.contains("delta"))
        assertTrue(adjust.requiredSlots.contains("direction"))

        val set = TemperatureToolBoundaryCatalog.forTool("climate.temperature.set")
        assertNotNull(set)
        assertTrue(set!!.operationSemantics.contains(TemperatureOperationSemantic.ABSOLUTE_TARGET.name))
        assertTrue(set.operationSemantics.contains(TemperatureOperationSemantic.BOUND_TARGET.name))
        assertTrue(set.valueRoles.contains("target"))
        assertTrue(set.requiredSlots.contains("temperature"))
    }

    @Test
    fun `adjust 与 set 互为冲突集`() {
        val adjust = TemperatureToolBoundaryCatalog.forTool("climate.temperature.adjust")!!
        val set = TemperatureToolBoundaryCatalog.forTool("climate.temperature.set")!!
        assertTrue("climate.temperature.set" in adjust.conflictToolIds)
        assertTrue("climate.temperature.adjust" in set.conflictToolIds)
        assertTrue("climate.status.query" in adjust.conflictToolIds)
        assertTrue("climate.status.query" in set.conflictToolIds)
    }

    @Test
    fun `负边界区分 adjust 与 set 语义`() {
        val adjust = TemperatureToolBoundaryCatalog.forTool("climate.temperature.adjust")!!
        assertTrue(adjust.negativeBoundaries.any { it.contains("调到") })
        assertTrue(adjust.negativeBoundaries.any { it.contains("设为最低") })

        val set = TemperatureToolBoundaryCatalog.forTool("climate.temperature.set")!!
        assertTrue(set.negativeBoundaries.any { it.contains("调高") })
        assertTrue(set.negativeBoundaries.any { it.contains("升温") })
    }

    @Test
    fun `定性幅度词典首期固定映射`() {
        val catalog = TemperatureDeltaAliasCatalog
        assertEquals(0.5, TemperatureDeltaAliasCatalog.resolve("一丢丢")!!.canonicalStep)
        assertEquals(0.5, TemperatureDeltaAliasCatalog.resolve("半度")!!.canonicalStep)
        assertEquals(1.0, TemperatureDeltaAliasCatalog.resolve("一点")!!.canonicalStep)
        assertEquals(1.0, TemperatureDeltaAliasCatalog.resolve("一些")!!.canonicalStep)
        assertEquals(2.0, TemperatureDeltaAliasCatalog.resolve("明显一些")!!.canonicalStep)
        assertEquals("SMALL", TemperatureDeltaAliasCatalog.resolve("一丢丢")!!.qualitativeValue)
        assertEquals("LARGE", TemperatureDeltaAliasCatalog.resolve("明显一些")!!.qualitativeValue)
        assertEquals(null, TemperatureDeltaAliasCatalog.resolve("两下"))
    }

    @Test
    fun `contentHash 稳定且内容变化反映到 Hash`() {
        val adjust = TemperatureToolBoundaryCatalog.forTool("climate.temperature.adjust")!!
        val set = TemperatureToolBoundaryCatalog.forTool("climate.temperature.set")!!
        assertEquals(adjust.contentHash, TemperatureToolBoundaryCatalog.contentHashOf(adjust))
        assertEquals(set.contentHash, TemperatureToolBoundaryCatalog.contentHashOf(set))
        // 内容变化 → Hash 变化（Catalog 变化必须触发 Embedding 重建）。
        val mutated = adjust.copy(positiveExamples = adjust.positiveExamples + "新正例")
        assertTrue(TemperatureToolBoundaryCatalog.contentHashOf(mutated) != adjust.contentHash)
    }
}
