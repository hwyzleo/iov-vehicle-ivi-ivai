package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.rag

import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagSaveResult
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService

/**
 * [RagConfigGateway] backed by the bound [AgentService].
 */
class ServiceRagConfigGateway(private val service: AgentService) : RagConfigGateway {

    override fun runtimeStatus(): RagRuntimeStatus = service.ragRuntimeStatus()

    override suspend fun load(): RagRuntimeConfig = service.ragConfigRepository.loadSnapshot()

    override suspend fun save(draft: RagConfigDraft): RagSaveResult =
        service.ragConfigRepository.save(draft)

    override suspend fun resetToDefault(): RagSaveResult =
        service.ragConfigRepository.resetToDefault()
}
