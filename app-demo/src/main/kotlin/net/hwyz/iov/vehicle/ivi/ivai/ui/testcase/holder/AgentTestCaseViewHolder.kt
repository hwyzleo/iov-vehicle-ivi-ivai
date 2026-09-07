package net.hwyz.iov.vehicle.ivi.ivai.ui.testcase.holder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner.AgentTestCaseStatus
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.scoring.FieldMatch
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.ui.testcase.AgentTestCaseUiModel

/**
 * 单条测试用例行（IVI-IVAI-DSN-CR-012 UI 设计）。
 * 列表默认展示概要（Case ID + 输入 + 状态 + 总分）；点击单条展开预期/实际差异，
 * 避免大量 JSON 使页面失去可读性。匹配状态同时使用图标（✓/✗）和文本，不只依赖颜色。
 */
class AgentTestCaseViewHolder(
    itemView: View,
    private val onClick: (String) -> Unit
) : RecyclerView.ViewHolder(itemView) {

    private val caseIdText: TextView = itemView.findViewById(R.id.caseIdText)
    private val inputText: TextView = itemView.findViewById(R.id.inputText)
    private val descriptionText: TextView = itemView.findViewById(R.id.descriptionText)
    private val statusText: TextView = itemView.findViewById(R.id.statusText)
    private val scoreText: TextView = itemView.findViewById(R.id.scoreText)
    private val detailContainer: View = itemView.findViewById(R.id.detailContainer)
    private val tierText: TextView = itemView.findViewById(R.id.tierText)
    private val domainText: TextView = itemView.findViewById(R.id.domainText)
    private val packText: TextView = itemView.findViewById(R.id.packText)
    private val targetText: TextView = itemView.findViewById(R.id.targetText)
    private val argsText: TextView = itemView.findViewById(R.id.argsText)
    private val errorText: TextView = itemView.findViewById(R.id.errorText)

    fun bind(model: AgentTestCaseUiModel) {
        caseIdText.text = model.caseId
        inputText.text = model.input
        descriptionText.text = model.description
        descriptionText.visibility = if (model.description.isNullOrBlank()) View.GONE else View.VISIBLE
        statusText.text = statusLabel(model.status)
        statusText.setTextColor(ContextCompat.getColor(itemView.context, statusColor(model.status)))
        scoreText.text = model.scoreTotal?.let { "$it/5" } ?: ""
        detailContainer.visibility = if (model.expanded) View.VISIBLE else View.GONE

        val score = model.score
        if (score != null) {
            tierText.text = dimensionLine("层级", score.tier)
            domainText.text = dimensionLine("领域", score.domain)
            packText.text = dimensionLine("能力包", score.capabilityPack)
            targetText.text = dimensionLine("Tool/Workflow", score.target)
            argsText.text = dimensionLine("参数", score.arguments)
        } else {
            tierText.text = "层级：—"
            domainText.text = "领域：—"
            packText.text = "能力包：—"
            targetText.text = "Tool/Workflow：—"
            argsText.text = "参数：—"
        }
        val error = model.errorMessage
        errorText.text = error
        errorText.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE

        itemView.setOnClickListener { onClick(model.caseId) }
    }

    private fun dimensionLine(label: String, field: FieldMatch): String {
        val mark = if (field.matched) "✓" else "✗"
        val expected = field.expected?.toString() ?: "无"
        val actual = field.actual?.toString() ?: "无"
        return "$label：$mark  预期[$expected] 实际[$actual]"
    }

    companion object {
        fun statusLabel(status: AgentTestCaseStatus): String = when (status) {
            AgentTestCaseStatus.PENDING -> "待执行"
            AgentTestCaseStatus.RUNNING -> "运行中"
            AgentTestCaseStatus.PASSED -> "通过"
            AgentTestCaseStatus.FAILED -> "失败"
            AgentTestCaseStatus.INCOMPLETE -> "不完整"
            AgentTestCaseStatus.TIMEOUT -> "超时"
            AgentTestCaseStatus.INVALID -> "无效"
            AgentTestCaseStatus.SKIPPED -> "跳过"
        }

        fun statusColor(status: AgentTestCaseStatus): Int = when (status) {
            AgentTestCaseStatus.PASSED -> R.color.status_ok
            AgentTestCaseStatus.FAILED, AgentTestCaseStatus.INVALID -> R.color.status_fail
            AgentTestCaseStatus.TIMEOUT -> R.color.status_timeout
            else -> R.color.text_secondary
        }

        fun create(parent: ViewGroup, onClick: (String) -> Unit): AgentTestCaseViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_agent_test_case, parent, false)
            return AgentTestCaseViewHolder(view, onClick)
        }
    }
}
