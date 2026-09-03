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
}
