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

    /** Confirmation id of the current pending confirmation, if any. */
    val pendingConfirmationId: String?
        get() = pendingTask?.takeIf { it.needConfirmation }?.confirmationId

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
        missingArguments: List<String>,
        confirmationId: String? = null,
        toolName: String? = null,
        requestId: String? = null,
        turnId: String? = null
    ) {
        pendingTask = PendingTask(
            intent = intent,
            needConfirmation = needConfirmation,
            missingArguments = missingArguments,
            createdAtMs = System.currentTimeMillis(),
            confirmationId = confirmationId,
            toolName = toolName,
            requestId = requestId,
            turnId = turnId
        )
    }

    fun consumePendingTask(): PendingTask? = pendingTask.also { pendingTask = null }

    fun clearPendingTask() {
        pendingTask = null
    }
}

/**
 * A task waiting for user follow-up: either a missing-argument dialogue or a confirmation.
 *
 * @param confirmationId non-null when [needConfirmation] is true; used by the idempotent
 *   confirm()/cancel() API (IVI-IVAI-DSN-CR-002).
 * @param requestId / [turnId] of the original turn, kept for event traceability when the
 *   user confirms or cancels later.
 */
data class PendingTask(
    val intent: Intent,
    val needConfirmation: Boolean,
    val missingArguments: List<String>,
    val createdAtMs: Long,
    val confirmationId: String? = null,
    val toolName: String? = null,
    val requestId: String? = null,
    val turnId: String? = null
)
