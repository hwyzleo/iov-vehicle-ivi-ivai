package net.hwyz.iov.vehicle.ivi.ivai.service

import kotlinx.coroutines.flow.Flow
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent

/**
 * Source of an [AgentCommand] (IVI-IVAI-DSN-CR-004). Chatbot, future ASR and
 * other clients share the same agent pipeline via this provenance tag.
 */
enum class AgentInputSource {
    TEXT_CHAT,
    VOICE_ASR,
    SYSTEM,
    OTHER
}

/**
 * Unified command contract for the [AiAgentClient] (IVI-IVAI-DSN-CR-004).
 * A command always carries its session, request and input source so the service
 * can enforce per-session single-turn policy and route provenance.
 */
sealed interface AgentCommand {
    val sessionId: String
    val requestId: String
    val inputSource: AgentInputSource

    /** A new user utterance for the session. */
    data class HandleText(
        override val sessionId: String,
        override val requestId: String,
        override val inputSource: AgentInputSource,
        val text: String,
        val turnId: String = requestId
    ) : AgentCommand

    /** Approve a pending confirmation. */
    data class Confirm(
        override val sessionId: String,
        override val requestId: String,
        override val inputSource: AgentInputSource,
        val confirmationId: String
    ) : AgentCommand

    /** Cancel a pending confirmation (no tool is ever executed). */
    data class Cancel(
        override val sessionId: String,
        override val requestId: String,
        override val inputSource: AgentInputSource,
        val confirmationId: String
    ) : AgentCommand
}

/**
 * Read-only session snapshot used to reconcile a reconnecting client
 * (IVI-IVAI-DSN-CR-004). History entries are role + text pairs; message-level
 * dedup stays on the client side via its local messageId/requestId index.
 */
data class AgentSessionSnapshot(
    val sessionId: String,
    val history: List<HistoryEntry>,
    val pendingConfirmationId: String?,
    val activeTurnId: String?
) {
    data class HistoryEntry(val role: String, val text: String)
}

/**
 * Stable, service-owned client contract (IVI-IVAI-DSN-CR-004): Chatbot, future
 * ASR and any other client depend only on this interface — never on
 * ModelProvider / ToolExecutor / Adapter. The Android implementation is hosted
 * by [AgentService]; process-internal binding via interface adaptation first,
 * Parcelable/AIDL only when cross-process is needed.
 */
interface AiAgentClient {

    /**
     * Submits a command to the agent service. The service owns Session,
     * PendingTask, PendingConfirmation, idempotency records and the active-turn
     * gate. Returns false when the service rejected it (no session / busy /
     * already terminated).
     */
    suspend fun submit(command: AgentCommand): Boolean

    /** Streams stable, user-facing [AgentEvent]s for [sessionId]. */
    fun observeEvents(sessionId: String): Flow<AgentEvent>

    /** Current snapshot for reconnection reconciliation; throws when unavailable. */
    suspend fun getSessionSnapshot(sessionId: String): AgentSessionSnapshot
}

/** IVAI-SERVICE-* error codes (IVI-IVAI-DSN-CR-004). */
object ServiceErrorCode {
    /** Agent service not connected or already terminated. */
    const val SERVICE_UNAVAILABLE = "IVAI-SERVICE-001"

    /** Session snapshot unavailable. */
    const val SNAPSHOT_UNAVAILABLE = "IVAI-SERVICE-002"
}

/** Thrown by the service boundary when a client request cannot be served. */
class ServiceException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
