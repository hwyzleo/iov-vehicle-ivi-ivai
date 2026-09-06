package net.hwyz.iov.vehicle.ivi.ivai.agent.capability

import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainRouter
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.AgentContext
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.TextNormalizer
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.capability.CapabilityCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-008 验证设计 · CapabilityPackSelector 单元测试：
 *  - 领域 → 能力包过滤（P0 只选中 cabin.climate）。
 *  - 车型 / 软件版本 / 能力开关 / 治理状态过滤。
 *  - 不可变快照：同一请求后续路由/Prompt/执行使用同一快照。
 */
class CapabilityPackSelectorTest {

    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
    private val domainRouter = DomainRouter(registry)
    private val selector = CapabilityPackSelector()

    private fun select(text: String, ctx: AgentContext): CapabilitySnapshot {
        val decision = domainRouter.route(TextNormalizer.normalize(text), ctx)
        return selector.select(decision, ctx)
    }

    private fun context(softwareVersion: String? = "0.1.0", vehicleModel: String? = null) =
        AgentContext(requestId = "r", sessionId = "s", source = "mock", vehicleModel = vehicleModel, softwareVersion = softwareVersion)

    @Test
    fun `空调请求选中 cabin_climate 包并收敛候选空间`() {
        val snapshot = select("打开空调", context())
        assertEquals(listOf("cabin.climate"), snapshot.packs.map { it.packId })
        assertEquals("1.0", snapshot.packVersion)
        assertTrue(snapshot.filteredToolIds.containsAll(
            listOf("climate.power_on", "climate.temperature_increase", "climate.status_query")
        ))
        assertTrue(snapshot.filteredWorkflowIds.contains("cabin.camping_mode"))
        assertTrue(snapshot.filters.none { it == PackFilterReason.DOMAIN_MISMATCH })
    }

    @Test
    fun `能源请求无可用包时快照为空并记录过滤原因`() {
        val snapshot = select("打开充电设置", context())
        assertTrue(snapshot.packs.isEmpty())
        // P0 只启用空调包 → ENERGY 包为 DRAFT/未启用 → 被过滤。
        assertTrue(snapshot.filters.contains(PackFilterReason.DOMAIN_MISMATCH))
    }

    @Test
    fun `软件版本不满足时包被过滤`() {
        val snapshot = select("打开空调", context(softwareVersion = "0.0.1"))
        assertTrue(snapshot.packs.isEmpty())
        assertTrue(snapshot.filters.contains(PackFilterReason.VERSION_MISMATCH))
    }

    @Test
    fun `未分类请求返回空快照`() {
        val snapshot = select("胎压报警是什么意思", context())
        assertTrue(snapshot.packs.isEmpty())
        assertTrue(snapshot.filteredToolIds.isEmpty())
    }

    @Test
    fun `能力开关过滤`() {
        val decision = domainRouter.route(TextNormalizer.normalize("打开空调"), context())
        // 空调包无 requiredFeatures → 任意 features 均可选中。
        val snapshot = selector.select(decision, context(), enabledFeatures = emptySet())
        assertFalse(snapshot.packs.isEmpty())
        assertEquals(CapabilityCatalog.CABIN_CLIMATE, snapshot.packs.first())
    }

    @Test
    fun `快照不可变且不暴露未批准包`() {
        val snapshot = select("打开空调", context())
        assertTrue(snapshot.packs.all { it.available })
        assertTrue(snapshot.packs.all { it.packId == "cabin.climate" })
    }
}
