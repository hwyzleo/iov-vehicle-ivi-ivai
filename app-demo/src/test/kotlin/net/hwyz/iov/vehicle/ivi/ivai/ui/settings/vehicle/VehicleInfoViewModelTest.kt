package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.vehicle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehicleFeatureSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehicleDomainInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehiclePackInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehicleToolInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehicleWorkflowInfo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 本车功能页（IVI-IVAI-DSN-CR-009）：快照加载、不可用状态与页签切换。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleInfoViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeGateway(private val snapshot: VehicleFeatureSnapshot?) : VehicleInfoGateway {
        override fun vehicleFeatureSnapshot(): VehicleFeatureSnapshot? = snapshot
    }

    private val snapshot = VehicleFeatureSnapshot(
        baselineVersion = "ivai-governance-v1",
        sourceCatalogVersion = "ivai-source-v1",
        domains = listOf(VehicleDomainInfo("BD01", "座舱舒适")),
        packs = listOf(
            VehiclePackInfo("cabin.climate", "空调与温控", "BD01", 13, 1, "P0")
        ),
        tools = listOf(
            VehicleToolInfo("climate.power.set", "设置空调电源", "BD01", "cabin.climate", "CONTROL")
        ),
        workflows = listOf(
            VehicleWorkflowInfo(
                workflowId = "workflow.camping",
                name = "露营",
                ownerDomainCode = "BD01",
                domainCodes = listOf("BD01", "BD02"),
                stepToolIds = listOf("climate.power.set", "body.window.set"),
                failurePolicy = "环境证据不足时追问"
            )
        )
    )

    @Test
    fun `loads snapshot and renders available`() = runTest(dispatcher) {
        val viewModel = VehicleInfoViewModel()
        viewModel.attach(FakeGateway(snapshot))
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.isAvailable)
        assertEquals(snapshot, state.snapshot)
        assertEquals(null, state.message)
        assertEquals(1, state.snapshot?.domainCount)
        assertEquals(1, state.snapshot?.packCount)
        assertEquals(1, state.snapshot?.toolCount)
        assertEquals(1, state.snapshot?.workflowCount)
    }

    @Test
    fun `null snapshot marks unavailable with message`() = runTest(dispatcher) {
        val viewModel = VehicleInfoViewModel()
        viewModel.attach(FakeGateway(null))
        runCurrent()

        val state = viewModel.state.value
        assertFalse(state.isAvailable)
        assertEquals(null, state.snapshot)
        assertTrue(state.message != null)
    }

    @Test
    fun `selectTab switches the active tab`() = runTest(dispatcher) {
        val viewModel = VehicleInfoViewModel()
        assertEquals(VehicleInfoTab.DOMAIN, viewModel.state.value.selectedTab)

        viewModel.selectTab(VehicleInfoTab.TOOL)
        assertEquals(VehicleInfoTab.TOOL, viewModel.state.value.selectedTab)

        // Selecting the same tab is a no-op.
        viewModel.selectTab(VehicleInfoTab.TOOL)
        assertEquals(VehicleInfoTab.TOOL, viewModel.state.value.selectedTab)

        viewModel.selectTab(VehicleInfoTab.WORKFLOW)
        assertEquals(VehicleInfoTab.WORKFLOW, viewModel.state.value.selectedTab)
    }
}
