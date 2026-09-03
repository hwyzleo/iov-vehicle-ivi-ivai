package net.hwyz.iov.vehicle.ivi.ivai.agent.testutil

import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEventListener

/**
 * Collects [AgentEvent]s in order for workflow event assertions.
 */
class CollectingAgentEventListener : AgentEventListener {

    val events = mutableListOf<AgentEvent>()

    override fun onAgentEvent(event: AgentEvent) {
        events += event
    }

    fun clear() = events.clear()

    fun ofType(klass: Class<out AgentEvent>): List<AgentEvent> = events.filter { klass.isInstance(it) }
}
