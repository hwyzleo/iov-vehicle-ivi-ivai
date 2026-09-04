package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.prompt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptSnapshot

/**
 * Loads the read-only [PromptSnapshot] from the agent service and applies the
 * release feature flag (full template only in debug builds, CR-004).
 */
class PromptInfoViewModel : ViewModel() {

    private val _state = MutableStateFlow(PromptInfoUiState())
    val state: StateFlow<PromptInfoUiState> = _state.asStateFlow()

    fun attach(gateway: PromptInfoGateway, showFullContent: Boolean) {
        viewModelScope.launch {
            val snapshot: PromptSnapshot? = runCatching { gateway.promptSnapshot() }.getOrNull()
            _state.value = when {
                snapshot == null -> PromptInfoUiState(isAvailable = false, showFullContent = showFullContent)
                showFullContent -> PromptInfoUiState(
                    promptVersion = snapshot.promptVersion,
                    updatedAt = snapshot.updatedAt,
                    content = snapshot.content,
                    isAvailable = true,
                    showFullContent = true
                )
                else -> PromptInfoUiState(
                    promptVersion = snapshot.promptVersion,
                    updatedAt = snapshot.updatedAt,
                    content = snapshot.content.take(SUMMARY_CHARS) +
                        if (snapshot.content.length > SUMMARY_CHARS) "…" else "",
                    isAvailable = true,
                    showFullContent = false
                )
            }
        }
    }

    private companion object {
        const val SUMMARY_CHARS = 200
    }
}
