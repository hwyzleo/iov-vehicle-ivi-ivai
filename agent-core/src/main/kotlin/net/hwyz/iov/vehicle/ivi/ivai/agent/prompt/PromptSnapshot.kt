package net.hwyz.iov.vehicle.ivi.ivai.agent.prompt

import kotlinx.serialization.Serializable

/**
 * Read-only snapshot of the currently effective prompt template
 * (IVI-IVAI-DSN-CR-004). Served by [PromptBuilder.snapshot] to the settings /
 * debug page. It deliberately contains no per-request runtime context (user
 * input, vehicle state, session) and no secrets — the UI must never mutate the
 * live prompt through it.
 */
@Serializable
data class PromptSnapshot(
    val promptVersion: String,
    val updatedAt: Long? = null,
    val content: String
)
