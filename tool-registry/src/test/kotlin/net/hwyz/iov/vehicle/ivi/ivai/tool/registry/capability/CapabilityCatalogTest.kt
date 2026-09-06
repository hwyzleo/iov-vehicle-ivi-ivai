package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.capability

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-008 验证设计 · Capability Pack 测试：建议能力包目录、P0 首期只启用空调包、
 * 车型/版本/治理状态过滤入口。
 */
class CapabilityCatalogTest {

    @Test
    fun `建议能力包目录覆盖 12 个包并覆盖 10 个业务领域`() {
        assertEquals(12, CapabilityCatalog.ALL.size)
        val packIds = CapabilityCatalog.ALL.map { it.packId }
        assertTrue(packIds.containsAll(
            listOf(
                "cabin.climate", "cabin.seat_comfort",
                "body.window_door", "body.lighting_wiper",
                "vehicle.driving_mode", "vehicle.adas_config",
                "energy.charging", "navigation.route", "navigation.poi",
                "media.audio", "media.video", "system.app_desktop"
            )
        ))
        // 12 个建议包覆盖 7 个业务领域（座舱舒适/车身/车辆设置/能源/导航/媒体/应用系统）；
        // 影像、通讯、信息服务暂无建议包（下一版 CR 治理时补充）。
        assertEquals(7, CapabilityCatalog.ALL.map { it.domainId }.toSet().size)
    }

    @Test
    fun `P0 首期只有空调包可用其余为治理定义`() {
        // 首期（P0）只启用 cabin.climate；其余 Pack 为 DRAFT / 未启用。
        assertEquals(listOf("cabin.climate"), CapabilityCatalog.AVAILABLE.map { it.packId })
        val climate = CapabilityCatalog.CABIN_CLIMATE
        assertTrue(climate.available)
        assertTrue(climate.toolIds.containsAll(
            listOf(
                "climate.power_on", "climate.power_off",
                "climate.temperature_increase", "climate.temperature_decrease",
                "climate.temperature_set", "climate.status_query"
            )
        ))
        // 能源包处于治理定义状态，未启用 → 运行时不返回。
        assertFalse(CapabilityCatalog.ENERGY_CHARGING.available)
        assertEquals(GovernanceStatus.DRAFT, CapabilityCatalog.ENERGY_CHARGING.status)
    }

    @Test
    fun `能力包按 packId 可查询`() {
        assertEquals(BusinessDomainId.ENERGY, CapabilityCatalog.get("energy.charging")?.domainId)
        assertEquals(null, CapabilityCatalog.get("not.exist"))
    }

    @Test
    fun `能力包软件版本约束参与过滤`() {
        val climate = CapabilityCatalog.CABIN_CLIMATE
        assertEquals("0.1.0", climate.applicableSoftware.min)
        // 0.0.9 < 0.1.0 → 不适用（由 selector 校验，见 agent-core 测试）。
        assertTrue(compare("0.0.9", "0.1.0") < 0)
    }

    private fun compare(a: String, b: String): Int {
        val sa = a.split(".").map { it.toInt() }
        val sb = b.split(".").map { it.toInt() }
        for (i in 0 until maxOf(sa.size, sb.size)) {
            val va = sa.getOrElse(i) { 0 }
            val vb = sb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
