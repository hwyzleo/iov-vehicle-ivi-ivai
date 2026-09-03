package net.hwyz.iov.vehicle.ivi.ivai.agent.session

import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentRoute
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.Intent
import net.hwyz.iov.vehicle.ivi.ivai.model.ChatMessage
import java.util.UUID

/**
 * Conversation state for one session: history, last route and the resumable pending task.
 */
class Session(val sessionId: String = UUID.randomUUID().toString()) {

    private val history = mutableListOf<ChatMessage>()

    var pendingTask: PendingTask? = null
        private set

    var lastRoute: AgentRoute? = null
        private set

    fun appendUser(text: String) {
        history += ChatMessage("user", text)
    }

    fun appendAssistant(text: String) {
        history += ChatMessage("assistant", text)
    }

    fun history(): List<ChatMessage> = history.toList()

    fun setRoute(route: AgentRoute) {
        lastRoute = route
    }

    fun storePendingTask(
        intent: Intent,
        needConfirmation: Boolean,
        missingArguments: List<String>
    ) {
        pendingTask = PendingTask(
            intent = intent,
            needConfirmation = needConfirmation,
            missingArguments = missingArguments,
            createdAtMs = System.currentTimeMillis()
        )
    }

    fun consumePendingTask(): PendingTask? = pendingTask.also { pendingTask = null }

    fun clearPendingTask() {
        pendingTask = null
    }
}

/**
 * A task waiting for user follow-up: either a missing-argument dialogue or a confirmation.
 */
data class PendingTask(
    val intent: Intent,
    val needConfirmation: Boolean,
    val missingArguments: List<String>,
    val createdAtMs: Long
)
