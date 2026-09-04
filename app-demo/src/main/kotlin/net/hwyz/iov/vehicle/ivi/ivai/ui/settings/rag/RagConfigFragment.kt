package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.rag

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.demo.R

/**
 * RAG settings group (CR-005): 检索增强 — 总开关 + Tool/Knowledge 子开关 +
 * 运行状态与配置反馈。打开开关不等于运行时已可用：只有索引 / Embedding /
 * 版本 / 完整性校验全部通过才进入 READY。
 */
class RagConfigFragment : Fragment() {

    private val viewModel: RagConfigViewModel by viewModels()

    private lateinit var masterSwitch: Switch
    private lateinit var toolSwitch: Switch
    private lateinit var knowledgeSwitch: Switch
    private lateinit var statusView: TextView
    private lateinit var messageView: TextView
    private lateinit var resetButton: Button

    private var updating = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_rag_config, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        masterSwitch = view.findViewById(R.id.ragEnabled)
        toolSwitch = view.findViewById(R.id.ragToolEnabled)
        knowledgeSwitch = view.findViewById(R.id.ragKnowledgeEnabled)
        statusView = view.findViewById(R.id.ragRuntimeStatus)
        messageView = view.findViewById(R.id.ragMessage)
        resetButton = view.findViewById(R.id.ragReset)

        masterSwitch.setOnCheckedChangeListener(onChecked(masterSwitch) { viewModel.setEnabled(it) })
        toolSwitch.setOnCheckedChangeListener(onChecked(toolSwitch) { viewModel.setToolRagEnabled(it) })
        knowledgeSwitch.setOnCheckedChangeListener(
            onChecked(knowledgeSwitch) { viewModel.setKnowledgeRagEnabled(it) }
        )
        resetButton.setOnClickListener { viewModel.resetToDefault() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    fun attach(gateway: RagConfigGateway) {
        viewModel.attach(gateway)
    }

    private fun onChecked(
        switch: Switch,
        action: (Boolean) -> Unit
    ): CompoundButton.OnCheckedChangeListener =
        CompoundButton.OnCheckedChangeListener { _, checked ->
            if (updating) return@OnCheckedChangeListener
            action(checked)
        }

    private fun render(state: RagConfigUiState) {
        updating = true
        masterSwitch.isChecked = state.enabled
        toolSwitch.isChecked = state.toolRagEnabled
        knowledgeSwitch.isChecked = state.knowledgeRagEnabled
        toolSwitch.isEnabled = state.enabled
        knowledgeSwitch.isEnabled = state.enabled
        updating = false

        statusView.text = "运行状态：${state.runtimeStatus}"
        if (state.runtimeStatus != "READY" && state.enabled) {
            statusView.append("\n（开关已打开，但资源未全部就绪 — 按策略降级）")
        }
        messageView.text = state.message ?: ""
        messageView.visibility = if (state.message == null) View.GONE else View.VISIBLE
        resetButton.isEnabled = state.loaded && !state.saving
        masterSwitch.isEnabled = state.loaded && !state.saving
    }

    companion object {
        fun newInstance(): RagConfigFragment = RagConfigFragment()
    }
}
