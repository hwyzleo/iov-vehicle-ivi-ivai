package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

/**
 * User intents dispatched from the chat screen to the ViewModel.
 */
sealed interface ChatUiAction {
    data class InputChanged(val text: String) : ChatUiAction
    data object SendClicked : ChatUiAction
    data class ConfirmClicked(val confirmationId: String) : ChatUiAction
    data class CancelClicked(val confirmationId: String) : ChatUiAction
    data class RetryClicked(val messageId: String) : ChatUiAction

    /** Toggle the collapsible performance detail panel of one final message (CR-004). */
    data class TogglePerformanceDetails(val messageId: String) : ChatUiAction

    // --- Push-to-talk (IVI-IVAI-DSN-CR-006) ---

    /** ACTION_DOWN on the voice button. */
    data object VoiceButtonDown : ChatUiAction

    /** ACTION_UP on the voice button (stop + finalize). */
    data object VoiceButtonUp : ChatUiAction

    /** ACTION_CANCEL / finger left the cancel area / page lost focus. */
    data object VoiceButtonCancel : ChatUiAction
}
