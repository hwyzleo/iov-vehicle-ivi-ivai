package net.hwyz.iov.vehicle.ivi.ivai.model.lifecycle

/**
 * 模型请求分阶段超时策略（IVI-IVAI-DSN-CR-016）。
 *
 * 拆分单一请求超时为五类超时，防止批量运行中残留请求、连接泄漏和队列堆积：
 *  - [queueTimeoutMs]：请求在队列中等待执行的超时（IVAI-MODEL-TIMEOUT-001）；
 *  - [firstOutputTimeoutMs]：从请求开始到首个可消费输出的超时
 *    （IVAI-MODEL-TIMEOUT-002；Tool Call-only 输出的首个结构化可消费事件也算）；
 *  - [idleChunkTimeoutMs]：流式响应连续无输出块的超时（IVAI-MODEL-TIMEOUT-003）；
 *  - [totalTimeoutMs]：模型总生成超时（IVAI-MODEL-TIMEOUT-004）；
 *  - [cleanupTimeoutMs]：Provider 取消/资源清理确认超时（IVAI-MODEL-CLEANUP-001）。
 */
data class ModelTimeoutPolicy(
    val queueTimeoutMs: Long = 10_000,
    val firstOutputTimeoutMs: Long = 20_000,
    val idleChunkTimeoutMs: Long = 15_000,
    val totalTimeoutMs: Long = 60_000,
    val cleanupTimeoutMs: Long = 5_000
) {
    companion object {
        /** 兼容默认（等价于原单一 requestTimeoutMs=60s）。 */
        val DEFAULT = ModelTimeoutPolicy()
    }
}

/**
 * 请求级模型调用报告（IVI-IVAI-DSN-CR-016）。
 * 记录 queue / first output / complete / cancel propagation / cleanup / retry
 * 等分阶段耗时（毫秒），供批量运行稳定性观测。
 */
data class ModelCallReport(
    val queueMs: Long? = null,
    val firstOutputMs: Long? = null,
    val totalMs: Long? = null,
    val idleTimeoutHit: Boolean = false,
    val cleanupMs: Long? = null,
    val retryCount: Int = 0,
    /** 命中的分阶段超时细分错误码（IVAI-MODEL-TIMEOUT-00x），未超时为 null。 */
    val timeoutErrorCode: String? = null
)
