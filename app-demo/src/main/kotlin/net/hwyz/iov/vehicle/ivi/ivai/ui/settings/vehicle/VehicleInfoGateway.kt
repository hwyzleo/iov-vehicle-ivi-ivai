package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.vehicle

import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehicleFeatureSnapshot

/**
 * 本车功能只读快照源（IVI-IVAI-DSN-CR-009）。UI 永不通过该接口变更运行时，
 * 仅渲染治理目录投影。
 */
interface VehicleInfoGateway {
    fun vehicleFeatureSnapshot(): VehicleFeatureSnapshot?
}

/** 本车功能页签（领域 / 能力包 / 工具 / 工作流）。 */
enum class VehicleInfoTab {
    DOMAIN,
    PACK,
    TOOL,
    WORKFLOW
}

/**
 * 本车功能页状态：治理目录快照 + 当前页签。页签保存在状态中，
 * 配置变更（旋转）后由 ViewModel 恢复。
 */
data class VehicleInfoUiState(
    val snapshot: VehicleFeatureSnapshot? = null,
    val isAvailable: Boolean = false,
    val selectedTab: VehicleInfoTab = VehicleInfoTab.DOMAIN,
    val message: String? = null
)
