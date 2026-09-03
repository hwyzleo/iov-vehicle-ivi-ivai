package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

/**
 * Runtime configuration for the agent workflow.
 */
data class AgentConfig(
    val model: String,
    val ollamaBaseUrl: String,
    val requestTimeoutMs: Long = 60_000,
    val executionTimeoutMs: Long = 10_000,
    val confirmationKeyword: String = "确认"
)
