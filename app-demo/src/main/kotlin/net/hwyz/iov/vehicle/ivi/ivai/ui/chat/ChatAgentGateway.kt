package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import kotlinx.coroutines.flow.Flow
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.service.SessionSnapshot

/**
 * Abstraction between the ViewModel and the AiAgentService / agent-core
 * (IVI-IVAI-DSN-CR-002). Kept as an interface so the ViewModel is unit-testable.
 */
interface ChatAgentGateway {
    /** Stable agent event stream the ViewModel maps into messages. */
    val events: Flow<AgentEvent>

    /**
     * Submits a user utterance as a new turn using the caller-provided [requestId];
     * returns it, or null when another turn is already active (single-turn policy).
     */
    fun submit(text: String, turnId: String, requestId: String): String?

    /** Approves a pending confirmation; false when another turn is active. */
    fun confirm(confirmationId: String): Boolean

    /** Cancels a pending confirmation (no tool executed); false when busy. */
    fun cancel(confirmationId: String): Boolean

    /** Current session snapshot for reconciliation after reconnection. */
    fun sessionSnapshot(): SessionSnapshot

    fun sessionId(): String
}
