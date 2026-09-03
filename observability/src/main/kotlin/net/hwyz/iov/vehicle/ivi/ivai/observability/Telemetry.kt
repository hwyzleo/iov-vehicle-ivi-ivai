package net.hwyz.iov.vehicle.ivi.ivai.observability

/**
 * A single observability record produced by the agent workflow per phase.
 */
data class TelemetryRecord(
    val requestId: String,
    val sessionId: String,
    val state: String,
    val route: String? = null,
    val latencyMs: Long = -1,
    val errorCode: String? = null,
    val toolId: String? = null,
    val toolStatus: String? = null
)

/**
 * Records agent telemetry. Implementations may log, forward or aggregate.
 */
interface TelemetryRecorder {
    fun record(record: TelemetryRecord)
}

/**
 * Console-recording telemetry (JVM-friendly, injectable output sink).
 */
class LoggingTelemetryRecorder(
    private val out: (String) -> Unit = { println(it) }
) : TelemetryRecorder {
    override fun record(record: TelemetryRecord) {
        out(
            buildString {
                append("[ivai] state=").append(record.state)
                record.route?.let { append(" route=").append(it) }
                record.errorCode?.let { append(" error=").append(it) }
                record.toolId?.let { append(" tool=").append(it) }
                record.toolStatus?.let { append(" toolStatus=").append(it) }
                if (record.latencyMs >= 0) append(" latency=").append(record.latencyMs).append("ms")
                append(" req=").append(record.requestId)
                append(" session=").append(record.sessionId)
            }
        )
    }
}

/**
 * Keeps all records in memory (used by tests / metrics computation).
 */
class CollectingTelemetryRecorder : TelemetryRecorder {
    private val list = java.util.concurrent.CopyOnWriteArrayList<TelemetryRecord>()
    val collected: List<TelemetryRecord> get() = list.toList()
    override fun record(record: TelemetryRecord) {
        list.add(record)
    }
    fun clear() = list.clear()
}

/**
 * Per-request summary attached to an agent result.
 */
data class RequestTelemetry(
    val requestId: String,
    val modelLatencyMs: Long = -1,
    val totalLatencyMs: Long = 0,
    val route: String? = null,
    val toolExecuted: Boolean = false,
    val validJson: Boolean = false,
    val schemaPassed: Boolean = false
)
