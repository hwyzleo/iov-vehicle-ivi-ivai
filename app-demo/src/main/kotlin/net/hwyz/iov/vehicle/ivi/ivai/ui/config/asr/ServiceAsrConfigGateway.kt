package net.hwyz.iov.vehicle.ivi.ivai.ui.config.asr

import kotlinx.coroutines.flow.StateFlow
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrConfigState

/** Real [AsrConfigGateway] backed by the shared repository from AgentService. */
class ServiceAsrConfigGateway(private val service: AgentService) : AsrConfigGateway {

    override val configState: StateFlow<AsrConfigState> get() = service.asrConfigRepository.configState

    override suspend fun keyStatus(): KeyStatus = service.asrConfigRepository.keyStatus()

    override suspend fun validate(draft: AsrConfigDraft): ValidationResult =
        service.asrConfigRepository.validate(draft)

    override suspend fun testConnection(draft: AsrConfigDraft): ConnectionTestResult =
        service.asrConfigRepository.testConnection(draft)

    override suspend fun save(draft: AsrConfigDraft): SaveResult =
        service.asrConfigRepository.save(draft)

    override suspend fun resetToDefault(): SaveResult =
        service.asrConfigRepository.resetToDefault()
}
