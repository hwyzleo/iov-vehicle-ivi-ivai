package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-010 验证设计 · 全量 Tool 确定性覆盖统计。
 *
 *  - 全部运行时可执行 Tool 都在统一候选集中；L0 命中由请求级确定性匹配动态计算，
 *    不存在固定 L0 白名单。
 *  - 6 个旧空调 ID 全部映射到 4 个 canonical Tool，且不在 160 目录中（不另计数量）。
 *  - Alias 引用闭合（canonical 目标必须存在于目录，IVAI-CAP-003 反向验证）。
 *  - 覆盖率按 Tool / Alias / OperationType 统计；P0～P3 只表示交付顺序，不参与
 *    运行时判断。
 */
class DeterministicCoverageTest {

    @Test
    fun `全部确定性画像的 Tool 均存在于 160 治理目录`() {
        val catalogIds = ToolCatalogV1.toolIds
        for ((toolId, profile) in ToolAliasCatalog.PROFILES) {
            assertTrue(toolId in catalogIds, "画像 Tool $toolId 必须在 160 目录中")
            assertEquals(toolId, profile.toolId)
            profile.rules.forEach { rule ->
                assertEquals(toolId, rule.toolId, "规则必须指向自身画像 Tool")
                assertTrue(rule.exactPhrases.isNotEmpty() || rule.synonymPatterns.isNotEmpty(), "规则必须有可匹配表达")
            }
        }
    }

    @Test
    fun `旧空调 ID canonical 映射闭合且全部解析到 160 目录内 Tool`() {
        val legacy = ToolAliasCatalog.legacyToCanonical()
        // 6 个旧 P0 空调 ID 全部映射。
        for (oldId in listOf(
            "climate.power_on", "climate.power_off",
            "climate.temperature_increase", "climate.temperature_decrease",
            "climate.temperature_set", "climate.status_query"
        )) {
            val canonical = legacy[oldId]
            assertNotNull(canonical, "旧 ID $oldId 必须映射到 canonical")
            assertTrue(canonical!! in ToolCatalogV1.toolIds, "$oldId → $canonical 必须在 160 目录中")
            assertFalse(oldId in ToolCatalogV1.toolIds, "旧 ID $oldId 不得计入 160 目录数量")
        }
        // 映射目标符合 CR-010 迁移表。
        assertEquals("climate.power.set", legacy["climate.power_on"])
        assertEquals("climate.power.set", legacy["climate.power_off"])
        assertEquals("climate.temperature.set", legacy["climate.temperature_set"])
        assertEquals("climate.temperature.adjust", legacy["climate.temperature_increase"])
        assertEquals("climate.temperature.adjust", legacy["climate.temperature_decrease"])
        assertEquals("climate.status.query", legacy["climate.status_query"])
    }

    @Test
    fun `Function-ID Alias 保留且映射闭合`() {
        val legacy = ToolAliasCatalog.legacyToCanonical()
        assertEquals("climate.power.set", legacy["AC_Control_1"])
        assertEquals("climate.power.set", legacy["AC_Control_2"])
        assertEquals("climate.temperature.set", legacy["AC_Temperature_1"])
        assertEquals("climate.temperature.adjust", legacy["AC_Temperature_2"])
        assertEquals("climate.temperature.adjust", legacy["AC_Temperature_3"])
    }

    @Test
    fun `确定性覆盖按 OperationType 统计`() {
        val profiles = ToolAliasCatalog.PROFILES
        val byOp = profiles.values
            .mapNotNull { ToolCatalogV1.get(it.toolId) }
            .groupingBy { it.operationType }
            .eachCount()
        // P0 显式表达覆盖 CONTROL + QUERY（空调电源/温度/状态 + 座椅按摩）。
        assertTrue((byOp[OperationType.CONTROL] ?: 0) >= 4)
        assertTrue((byOp[OperationType.QUERY] ?: 0) >= 1)
        // 覆盖率只是建设进度；未覆盖的 Tool 属于治理缺口（IVAI-ROUTE-004），
        // 不是「天生只能走 L1」。
        assertTrue(profiles.size <= ToolCatalogV1.ALL.size)
    }

    @Test
    fun `160 目录数量不因旧 ID 增加`() {
        assertEquals(160, ToolCatalogV1.ALL.size)
        assertFalse("climate.power_on" in ToolCatalogV1.toolIds)
        assertFalse("climate.temperature_increase" in ToolCatalogV1.toolIds)
    }
}
