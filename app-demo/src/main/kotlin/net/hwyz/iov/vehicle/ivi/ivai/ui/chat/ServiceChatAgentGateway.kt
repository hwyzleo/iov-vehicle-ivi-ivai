package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import kotlinx.coroutines.flow.Flow
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentCommand
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentInputSource
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentSessionSnapshot

/**
 * Real [ChatAgentGateway] backed by the bound [AgentService], which implements
 * the [AiAgentClient] contract (IVI-IVAI-DSN-CR-004).
 */
class ServiceChatAgentGateway(private val service: AgentService) : ChatAgentGateway {

    override suspend fun submit(command: AgentCommand): Boolean =
        service.submit(command)

    override fun observeEvents(sessionId: String): Flow<AgentEvent> =
        service.observeEvents(sessionId)

    override suspend fun getSessionSnapshot(sessionId: String): AgentSessionSnapshot =
        service.getSessionSnapshot(sessionId)

    override fun sessionId(): String = service.sessionId()

    override fun promptSnapshot(): PromptSnapshot? = service.promptSnapshot()
}
