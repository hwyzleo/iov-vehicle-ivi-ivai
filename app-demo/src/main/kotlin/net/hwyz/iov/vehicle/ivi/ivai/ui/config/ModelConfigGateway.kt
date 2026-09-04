package net.hwyz.iov.vehicle.ivi.ivai.ui.config

import kotlinx.coroutines.flow.StateFlow
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigState
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult

/**
 * Abstraction between the ViewModel and the shared [ModelConfigRepository]
 * (owned by AgentService), mirroring [net.hwyz.iov.vehicle.ivi.ivai.ui.chat.ChatAgentGateway]
 * so the ViewModel stays unit-testable.
 */
interface ModelConfigGateway {
    val configState: StateFlow<ModelConfigState>

    suspend fun keyStatus(): KeyStatus

    suspend fun validate(draft: ModelConfigDraft): ValidationResult

    suspend fun testConnection(draft: ModelConfigDraft): ConnectionTestResult

    suspend fun save(draft: ModelConfigDraft): SaveResult

    suspend fun resetToDefault(): SaveResult
}
