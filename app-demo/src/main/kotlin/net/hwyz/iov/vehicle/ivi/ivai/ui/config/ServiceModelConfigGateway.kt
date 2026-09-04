package net.hwyz.iov.vehicle.ivi.ivai.ui.config

import kotlinx.coroutines.flow.StateFlow
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigState
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult

/** Real [ModelConfigGateway] backed by the shared repository from AgentService. */
class ServiceModelConfigGateway(private val repository: ModelConfigRepository) : ModelConfigGateway {

    override val configState: StateFlow<ModelConfigState> get() = repository.configState

    override suspend fun keyStatus(): KeyStatus = repository.keyStatus()

    override suspend fun validate(draft: ModelConfigDraft): ValidationResult = repository.validate(draft)

    override suspend fun testConnection(draft: ModelConfigDraft): ConnectionTestResult =
        repository.testConnection(draft)

    override suspend fun save(draft: ModelConfigDraft): SaveResult = repository.save(draft)

    override suspend fun resetToDefault(): SaveResult = repository.resetToDefault()
}
