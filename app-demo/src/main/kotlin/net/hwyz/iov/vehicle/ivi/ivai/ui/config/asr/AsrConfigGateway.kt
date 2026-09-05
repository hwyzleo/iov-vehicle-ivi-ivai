package net.hwyz.iov.vehicle.ivi.ivai.ui.config.asr

import kotlinx.coroutines.flow.StateFlow
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrConfigState

/**
 * Abstraction between the ViewModel and the shared [AsrConfigRepository] owned
 * by AgentService (IVI-IVAI-DSN-CR-006), mirroring ModelConfigGateway.
 */
interface AsrConfigGateway {
    val configState: StateFlow<AsrConfigState>

    suspend fun keyStatus(): KeyStatus

    suspend fun validate(draft: AsrConfigDraft): ValidationResult

    suspend fun testConnection(draft: AsrConfigDraft): ConnectionTestResult

    suspend fun save(draft: AsrConfigDraft): SaveResult

    suspend fun resetToDefault(): SaveResult
}
