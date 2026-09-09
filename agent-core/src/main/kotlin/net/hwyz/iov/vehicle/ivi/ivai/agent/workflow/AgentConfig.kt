package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

/**
 * Runtime configuration for the agent workflow.
 */
data class AgentConfig(
    val model: String,
    val ollamaBaseUrl: String,
    val requestTimeoutMs: Long = 60_000,
    val executionTimeoutMs: Long = 10_000,
    val confirmationKeyword: String = "确认",
    /** Stream model output when the provider supports it (visible token-by-token + real TTFT). */
    val streamingEnabled: Boolean = true
) {
    companion object {
        /** 默认请求超时（与 CR-016 ModelTimeoutPolicy.totalTimeoutMs 对齐）。 */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 60_000L
    }
}
