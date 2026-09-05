package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import android.view.View
import android.widget.TextView
import net.hwyz.iov.vehicle.ivi.ivai.demo.R

/**
 * Binds [VoiceInputState] to the voice button and a small status line
 * (IVI-IVAI-DSN-CR-006). State changes are never expressed by color alone —
 * the button label, an optional icon and the hint text always change together.
 */
class VoiceInputUiBinder(
    private val voiceButton: View,
    private val voiceHint: TextView
) {

    /**
     * Binds the gesture state. [available] reflects whether a usable recognizer
     * exists (permission + Recognition Service / configured provider). The
     * button stays CLICKABLE even when unavailable so a press can explain the
     * reason (or request permission) — unavailability is shown by dimming
     * (alpha) instead of disabling.
     */
    fun bind(state: VoiceInputState, available: Boolean) {
        // Never disable the button: disabled views swallow touches, but we want
        // a press on a dimmed entry to surface the reason / request permission.
        voiceButton.isEnabled = true
        voiceButton.alpha = if (available) 1f else 0.4f

        voiceHint.visibility = View.VISIBLE
        when (state) {
            is VoiceInputState.Idle -> {
                voiceHint.visibility = View.GONE
            }
            is VoiceInputState.Checking -> {
                voiceHint.text = "检查语音能力…"
            }
            is VoiceInputState.Preparing -> {
                voiceHint.text = "准备中…"
            }
            is VoiceInputState.Listening -> {
                voiceHint.text = "正在聆听，松开识别" +
                    (state.partialText.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
            }
            is VoiceInputState.Finalizing -> {
                voiceHint.text = "正在识别…"
            }
            is VoiceInputState.Cancelled -> {
                voiceHint.text = "已取消"
            }
            is VoiceInputState.Failed -> {
                voiceHint.text = state.error.message
            }
        }
        // Button label reflects the active gesture (text + icon feedback).
        voiceButton.contentDescription =
            when (state) {
                is VoiceInputState.Listening -> "正在聆听，松开结束并识别"
                is VoiceInputState.Preparing, is VoiceInputState.Checking -> "语音识别准备中"
                is VoiceInputState.Finalizing -> "正在识别"
                else -> if (available) "按住说话" else "语音识别不可用，点击查看原因"
            }
    }

    companion object {
        /** Idle label, kept here so the activity and tests share one source of truth. */
        const val IDLE_LABEL = "按住说话"
    }
}
