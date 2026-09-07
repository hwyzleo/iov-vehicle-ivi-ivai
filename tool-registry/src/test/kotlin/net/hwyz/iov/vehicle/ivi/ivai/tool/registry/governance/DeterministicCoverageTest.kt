package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.DeterministicSupport
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-010 + CR-013 验证设计 · 全量 Tool 确定性资格统计。
 *
 *  - 160 个 Tool 全量完成 L0 资格评审；只有 SUPPORTED + 规则有效的 Tool 进入
 *    DeterministicIntentCatalog.productionToolIds（Matcher 确定性候选）。
 *  - NOT_SUPPORTED / NEEDS_REVIEW Tool 仍保留在统一运行时候选集（L1），不因
 *    不支持 L0 而从 CapabilitySnapshot 中删除（REQ-125）。
 *  - 6 个旧空调 ID 全部映射到 4 个 canonical Tool，且不在 160 目录中。
 *  - Alias 引用闭合（canonical 目标必须存在于目录，IVAI-CAP-003 反向验证）。
 *  - P0～P3 只表示交付顺序，不参与运行时判断。
 */
class DeterministicCoverageTest {

    private val catalog = DeterministicIntentCatalog.build()

    @Test
    fun `全部确定性画像的 Tool 均存在于 160 治理目录且规则指向自身`() {
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
    fun `160 项全量资格评审且仅 SUPPORTED 进入生产集合`() {
        assertEquals(160, ToolAliasCatalog.PROFILES.size, "CR-013：全部 160 Tool 都有资格画像")
        assertEquals(160, catalog.profiles.size)
        val production = catalog.productionToolIds
        assertEquals(102, production.size)
        for (profile in catalog.profiles.values) {
            assertEquals(profile.support == DeterministicSupport.SUPPORTED, profile.toolId in production)
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
        val production = catalog.productionToolIds
        val byOp = production
            .mapNotNull { ToolCatalogV1.get(it) }
            .groupingBy { it.operationType }
            .eachCount()
        // SUPPORTED 覆盖 CONTROL + QUERY + CONFIGURE + PLAYBACK + NAVIGATE_UI。
        assertTrue((byOp[OperationType.CONTROL] ?: 0) >= 4)
        assertTrue((byOp[OperationType.QUERY] ?: 0) >= 1)
        // 覆盖率只是建设进度；NOT_SUPPORTED 的 Tool 属于治理结论（L1），
        // 不是「天生只能走 L1」也不是缺口。
        assertTrue(production.size <= ToolCatalogV1.ALL.size)
    }

    @Test
    fun `160 目录数量不因旧 ID 增加`() {
        assertEquals(160, ToolCatalogV1.ALL.size)
        assertFalse("climate.power_on" in ToolCatalogV1.toolIds)
        assertFalse("climate.temperature_increase" in ToolCatalogV1.toolIds)
    }
}
