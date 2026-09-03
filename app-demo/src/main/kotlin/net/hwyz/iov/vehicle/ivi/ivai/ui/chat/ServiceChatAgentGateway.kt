package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import kotlinx.coroutines.flow.Flow
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService
import net.hwyz.iov.vehicle.ivi.ivai.service.SessionSnapshot

/**
 * Real [ChatAgentGateway] backed by the bound [AgentService].
 */
class ServiceChatAgentGateway(private val service: AgentService) : ChatAgentGateway {

    override val events: Flow<AgentEvent> get() = service.events

    override fun submit(text: String, turnId: String, requestId: String): String? =
        service.submit(text, turnId, requestId, source = "chat")

    override fun confirm(confirmationId: String): Boolean =
        service.confirm(confirmationId)

    override fun cancel(confirmationId: String): Boolean =
        service.cancel(confirmationId)

    override fun sessionSnapshot(): SessionSnapshot = service.sessionSnapshot()

    override fun sessionId(): String = service.sessionId()
}
