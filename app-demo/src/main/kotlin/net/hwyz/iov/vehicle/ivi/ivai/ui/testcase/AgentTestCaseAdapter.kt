package net.hwyz.iov.vehicle.ivi.ivai.ui.testcase

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.hwyz.iov.vehicle.ivi.ivai.ui.testcase.holder.AgentTestCaseViewHolder

/**
 * 测试用例列表 Adapter（IVI-IVAI-DSN-CR-012）。
 */
class AgentTestCaseAdapter(
    private val onToggleExpand: (String) -> Unit
) : ListAdapter<AgentTestCaseUiModel, RecyclerView.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        AgentTestCaseViewHolder.create(parent, onToggleExpand)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as AgentTestCaseViewHolder).bind(getItem(position))
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AgentTestCaseUiModel>() {
            override fun areItemsTheSame(oldItem: AgentTestCaseUiModel, newItem: AgentTestCaseUiModel) =
                oldItem.caseId == newItem.caseId

            override fun areContentsTheSame(oldItem: AgentTestCaseUiModel, newItem: AgentTestCaseUiModel) =
                oldItem == newItem
        }
    }
}
