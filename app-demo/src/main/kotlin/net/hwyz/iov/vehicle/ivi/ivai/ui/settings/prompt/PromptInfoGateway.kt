package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.prompt

import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptSnapshot

/**
 * Read-only snapshot source for the PromptInfo settings page (CR-004). The UI
 * never mutates the live prompt through this — it only renders the snapshot.
 */
interface PromptInfoGateway {
    fun promptSnapshot(): PromptSnapshot?
}

/**
 * PromptInfo screen state (IVI-IVAI-DSN-CR-004).
 *
 * Release builds (feature flag [showFullContent] = false) only display the
 * version and a short summary — the full template stays a debug-only surface.
 */
data class PromptInfoUiState(
    val promptVersion: String = "",
    val updatedAt: Long? = null,
    val content: String = "",
    val isAvailable: Boolean = false,
    val showFullContent: Boolean = true
)
