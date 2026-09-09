package net.hwyz.iov.vehicle.ivi.ivai.model.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Provider 清理屏障（IVI-IVAI-DSN-CR-016）。
 *
 * 批量串行执行时，TestBatchRunner 收到一条用例终态后，必须等待上一条 Provider
 * 请求的取消/资源清理完成（或达到独立清理超时）后才能执行下一条，防止残留
 * 推理请求、连接泄漏与队列堆积（REQ-164）。
 *
 * 生命周期（设计结论）：
 * ```plain text
 * QUEUED → REQUEST_STARTED → FIRST_CONSUMABLE_OUTPUT → STREAMING → COMPLETED
 *  或 TIMEOUT/CANCELLED/FAILED → TRANSPORT_CANCELLED → RESPONSE_BODY_CLOSED
 *  → CLEANUP_CONFIRMED
 * ```
 *
 * 使用方式（单 Runner 串行）：
 *  - 发起请求前 [beginRequest]；
 *  - 请求完成后（成功 / 超时 / 取消 / 失败）[markCleaned]；
 *  - 下一条请求前 [awaitCleanup] 确认清理或达到 [cleanupTimeoutMs] 独立超时。
 */
class ProviderCleanupBarrier(
    private val cleanupTimeoutMs: Long = ModelTimeoutPolicy.DEFAULT.cleanupTimeoutMs
) {

    /** 当前活动请求是否仍可能持有 Provider 资源。 */
    @Volatile
    private var activeRequestId: String? = null

    private var cleanupSignal = CompletableDeferred<Unit>()

    /**
     * 标记一个新请求进入活动（QUEUED）。若上一个请求尚未确认清理，先等待
     * 清理信号或清理超时（返回 false 表示超时未确认，由调用方记录
     * IVAI-MODEL-CLEANUP-001 但可继续）。
     */
    suspend fun beginRequest(requestId: String): Boolean {
        if (activeRequestId != null) {
            val confirmed = withTimeoutOrNull(cleanupTimeoutMs) {
                cleanupSignal.await()
            } ?: return false
            if (confirmed == null) return false
        }
        activeRequestId = requestId
        cleanupSignal = CompletableDeferred()
        return true
    }

    /**
     * 等待当前活动请求的清理确认（TRANSPORT_CANCELLED → RESPONSE_BODY_CLOSED →
     * CLEANUP_CONFIRMED）。返回 true 表示确认清理完成；false 表示达到清理超时
     * （IVAI-MODEL-CLEANUP-001，调用方应记录并决定是否继续）。
     */
    suspend fun awaitCleanup(): Boolean {
        val current = activeRequestId ?: return true
        val confirmed = withTimeoutOrNull(cleanupTimeoutMs) {
            cleanupSignal.await()
        } ?: return false
        if (confirmed == null) return false
        activeRequestId = null
        return true
    }

    /** 请求结束（成功 / 超时 / 取消 / 失败）后确认资源已清理。 */
    fun markCleaned() {
        if (!cleanupSignal.isCompleted) {
            cleanupSignal.complete(Unit)
        }
        activeRequestId = null
    }

    /** 当前是否有活动请求（任一时刻最多一个，供批量稳定性断言）。 */
    fun hasActiveRequest(): Boolean = activeRequestId != null

    /** 当前活动请求 ID（可观测性）。 */
    fun activeRequestId(): String? = activeRequestId
}
