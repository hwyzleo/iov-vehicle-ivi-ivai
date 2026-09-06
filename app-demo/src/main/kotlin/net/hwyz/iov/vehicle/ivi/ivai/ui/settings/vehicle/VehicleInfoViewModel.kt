package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehicleFeatureSnapshot

/**
 * 本车功能页（IVI-IVAI-DSN-CR-009）：从 AgentService 加载治理目录只读快照
 * 并维护当前页签（旋转后恢复）。快照加载失败时展示不可用提示。
 */
class VehicleInfoViewModel : ViewModel() {

    private val _state = MutableStateFlow(VehicleInfoUiState())
    val state: StateFlow<VehicleInfoUiState> = _state.asStateFlow()

    fun attach(gateway: VehicleInfoGateway) {
        viewModelScope.launch {
            val snapshot: VehicleFeatureSnapshot? =
                runCatching { gateway.vehicleFeatureSnapshot() }.getOrNull()
            _state.value = _state.value.copy(
                snapshot = snapshot,
                isAvailable = snapshot != null,
                message = if (snapshot == null) {
                    "本车功能快照不可用（服务未连接或未初始化）"
                } else {
                    null
                }
            )
        }
    }

    fun selectTab(tab: VehicleInfoTab) {
        if (_state.value.selectedTab == tab) return
        _state.value = _state.value.copy(selectedTab = tab)
    }
}
