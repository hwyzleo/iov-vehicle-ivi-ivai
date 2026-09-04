package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import kotlinx.coroutines.flow.Flow
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.service.AiAgentClient

/**
 * ViewModel-facing agent client (IVI-IVAI-DSN-CR-002 + CR-004).
 *
 * It IS an [AiAgentClient]: the ViewModel only talks to the stable service
 * contract (submit / observeEvents / getSessionSnapshot) — never to
 * ModelProvider, ToolExecutor or a vehicle Adapter. The extra accessors are
 * narrow UI conveniences for reconnection (sessionId) and the read-only prompt
 * debug entry.
 */
interface ChatAgentGateway : AiAgentClient {

    /** Stable agent event stream the ViewModel maps into messages. */
    override fun observeEvents(sessionId: String): Flow<AgentEvent>

    fun sessionId(): String

    /** Read-only prompt template snapshot for the settings / debug page. */
    fun promptSnapshot(): PromptSnapshot?
}
