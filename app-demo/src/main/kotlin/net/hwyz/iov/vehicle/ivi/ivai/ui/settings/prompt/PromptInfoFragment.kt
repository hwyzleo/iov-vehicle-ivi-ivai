package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.prompt

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.demo.R

/**
 * Read-only prompt info page (IVI-IVAI-DSN-CR-004). Shows the current effective
 * prompt template version / update time / content from the Prompt Builder
 * snapshot. Debug builds render the full template; release builds only the
 * version and a short summary (feature flag).
 */
class PromptInfoFragment : Fragment() {

    private val viewModel: PromptInfoViewModel by viewModels()

    private lateinit var versionView: TextView
    private lateinit var updatedAtView: TextView
    private lateinit var contentView: TextView
    private lateinit var unavailableView: TextView
    private lateinit var noticeView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_prompt_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        versionView = view.findViewById(R.id.promptVersion)
        updatedAtView = view.findViewById(R.id.promptUpdatedAt)
        contentView = view.findViewById(R.id.promptContent)
        unavailableView = view.findViewById(R.id.promptUnavailable)
        noticeView = view.findViewById(R.id.promptNotice)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    /**
     * Attaches the read-only snapshot source. Called by the host activity once
     * the agent service is bound (the fragment is recreated across config
     * changes, so [androidx.lifecycle.ViewModel] re-collects the last state).
     */
    fun attach(gateway: PromptInfoGateway, showFullContent: Boolean) {
        viewModel.attach(gateway, showFullContent)
    }

    private fun render(state: PromptInfoUiState) {
        if (!state.isAvailable) {
            versionView.visibility = View.GONE
            updatedAtView.visibility = View.GONE
            contentView.visibility = View.GONE
            noticeView.visibility = View.GONE
            unavailableView.visibility = View.VISIBLE
            return
        }
        unavailableView.visibility = View.GONE
        versionView.visibility = View.VISIBLE
        updatedAtView.visibility = View.VISIBLE
        contentView.visibility = View.VISIBLE

        versionView.text = "提示词版本：${state.promptVersion}"
        updatedAtView.text = state.updatedAt?.let { "更新于：${formatTime(it)}" } ?: "更新于：未知"
        contentView.text = state.content
        noticeView.visibility =
            if (state.showFullContent) View.VISIBLE else View.GONE
        noticeView.text = "以下为当前生效的提示词模板（只读，修改请通过构建/配置流程）。"
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

    companion object {
        fun newInstance(): PromptInfoFragment = PromptInfoFragment()
    }
}
