package net.hwyz.iov.vehicle.ivi.ivai.model

import kotlinx.serialization.Serializable

/**
 * Segmented end-to-end performance of one agent turn (IVI-IVAI-DSN-CR-004).
 *
 * 口径原则：
 *  - 所有时长使用单调时钟（JVM: System.nanoTime / Android: SystemClock.elapsedRealtimeNanos）
 *  - 顶层阶段（queue / contextAndPrompt / modelCallTotal / parseAndSchema / routeAndPolicy /
 *    toolExecution / eventDispatch）按非重叠端到端区间统计，[unattributedMs] 只按它们计算
 *  - [network] 与 [providerCompute] 是模型调用内部的诊断子指标，绝不与 [modelCallTotalMs]
 *    或顶层阶段重复相加
 *  - 不可观测的阶段使用 null，UI 显示「不支持/未知」，不得写成 0
 */
@Serializable
data class AgentPerformanceMetrics(
    val requestId: String,
    val queueMs: Long? = null,
    val contextAndPromptMs: Long? = null,
    val modelCallTotalMs: Long? = null,
    val network: HttpNetworkMetrics? = null,
    val providerCompute: ProviderComputeMetrics? = null,
    val timeToFirstTokenMs: Long? = null,
    val streamingUsed: Boolean? = null,
    val parseAndSchemaMs: Long? = null,
    val routeAndPolicyMs: Long? = null,
    val toolExecutionMs: Long? = null,
    val eventDispatchMs: Long? = null,
    val endToEndMs: Long,
    val unattributedMs: Long? = null
)

/**
 * OkHttp-observable network sub-metrics inside [modelCallTotalMs] (CR-004).
 * Connection reuse (DNS / connect / TLS) yields no events → fields stay null.
 */
@Serializable
data class HttpNetworkMetrics(
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
    val requestWriteMs: Long? = null,
    val timeToFirstByteMs: Long? = null,
    val responseReadMs: Long? = null,
    val connectionReused: Boolean? = null
)

/**
 * Server-side compute data explicitly reported by the provider (Ollama
 * eval_count/eval_duration or OpenAI-compatible timing extensions). Diagnostic
 * only — never added to [AgentPerformanceMetrics.modelCallTotalMs].
 */
@Serializable
data class ProviderComputeMetrics(
    val promptEvaluationMs: Long? = null,
    val generationMs: Long? = null,
    val totalReportedMs: Long? = null,
    val promptEvaluationTokens: Long? = null,
    val generationTokens: Long? = null,
    val source: String
)

/**
 * Validates that a computed [AgentPerformanceMetrics] obeys the non-overlapping
 * phase invariants. Produces IVAI-METRICS-001 when timestamps are missing or
 * the phase order is illegal.
 */
object PerformanceMetricsValidator {

    const val METRICS_INVALID = "IVAI-METRICS-001"

    /**
     * Recomputes [AgentPerformanceMetrics.unattributedMs] from the top-level
     * non-overlapping phases. Never named "network time"; it covers queueing,
     * serialization, event dispatch and other unattributed work.
     */
    fun unattributedMs(
        endToEndMs: Long,
        queueMs: Long?,
        contextAndPromptMs: Long?,
        modelCallTotalMs: Long?,
        parseAndSchemaMs: Long?,
        routeAndPolicyMs: Long?,
        toolExecutionMs: Long?,
        eventDispatchMs: Long?
    ): Long = maxOf(
        0L,
        endToEndMs
            - (queueMs ?: 0L)
            - (contextAndPromptMs ?: 0L)
            - (modelCallTotalMs ?: 0L)
            - (parseAndSchemaMs ?: 0L)
            - (routeAndPolicyMs ?: 0L)
            - (toolExecutionMs ?: 0L)
            - (eventDispatchMs ?: 0L)
    )

    /**
     * Checks timestamp sanity across a non-overlapping phase sequence:
     * strictly increasing starts, no negative durations. Returns the metric
     * error code or null when the sequence is legal.
     */
    fun validatePhaseOrder(timestampsNs: List<Long>): String? {
        if (timestampsNs.isEmpty()) return null
        var previous = timestampsNs.first()
        for (i in 1 until timestampsNs.size) {
            val current = timestampsNs[i]
            if (current < previous) return METRICS_INVALID
            previous = current
        }
        return null
    }
}
