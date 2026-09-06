package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.agent.vehicle.VehicleFeatureSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService

/**
 * 本车功能页（IVI-IVAI-DSN-CR-009）：设置页「本车功能」按钮进入的只读页。
 * 绑定 AgentService 获取治理目录快照，按「领域 / 能力包 / 工具 / 工作流」
 * 四个页签展示当前车辆的功能清单（中文名称 + 稳定代码）。
 */
class VehicleInfoActivity : ComponentActivity() {

    private val viewModel: VehicleInfoViewModel by viewModels()

    private lateinit var tabDomainButton: Button
    private lateinit var tabPackButton: Button
    private lateinit var tabToolButton: Button
    private lateinit var tabWorkflowButton: Button
    private lateinit var summaryView: TextView
    private lateinit var unavailableView: TextView
    private lateinit var contentView: TextView

    private var agentService: AgentService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AgentService.LocalBinder).getService()
            agentService = service
            viewModel.attach(ServiceVehicleInfoGateway(service))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle_info)

        tabDomainButton = findViewById(R.id.tabDomainButton)
        tabPackButton = findViewById(R.id.tabPackButton)
        tabToolButton = findViewById(R.id.tabToolButton)
        tabWorkflowButton = findViewById(R.id.tabWorkflowButton)
        summaryView = findViewById(R.id.vehicleSummary)
        unavailableView = findViewById(R.id.vehicleUnavailable)
        contentView = findViewById(R.id.vehicleContent)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        tabDomainButton.setOnClickListener { viewModel.selectTab(VehicleInfoTab.DOMAIN) }
        tabPackButton.setOnClickListener { viewModel.selectTab(VehicleInfoTab.PACK) }
        tabToolButton.setOnClickListener { viewModel.selectTab(VehicleInfoTab.TOOL) }
        tabWorkflowButton.setOnClickListener { viewModel.selectTab(VehicleInfoTab.WORKFLOW) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, AgentService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        unbindService(serviceConnection)
        agentService = null
        super.onStop()
    }

    override fun onBackPressed() {
        finish()
    }

    private fun render(state: VehicleInfoUiState) {
        renderTabs(state.selectedTab)
        if (!state.isAvailable || state.snapshot == null) {
            summaryView.visibility = View.GONE
            contentView.visibility = View.GONE
            unavailableView.visibility = View.VISIBLE
            unavailableView.text = state.message ?: "本车功能快照不可用"
            return
        }
        unavailableView.visibility = View.GONE
        summaryView.visibility = View.VISIBLE
        contentView.visibility = View.VISIBLE

        val snapshot = state.snapshot
        summaryView.text = buildString {
            append("治理基线 ${snapshot.baselineVersion}（source ${snapshot.sourceCatalogVersion}）\n")
            append("领域 ${snapshot.domainCount} · 能力包 ${snapshot.packCount} · 工具 ${snapshot.toolCount} · 工作流 ${snapshot.workflowCount}")
        }
        contentView.text = buildContent(snapshot, state.selectedTab)
    }

    private fun renderTabs(selected: VehicleInfoTab) {
        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_details)
        val defaultBg = null
        val activeColor = ContextCompat.getColor(this, R.color.text_primary)
        val defaultColor = ContextCompat.getColor(this, R.color.text_secondary)
        listOf(
            VehicleInfoTab.DOMAIN to tabDomainButton,
            VehicleInfoTab.PACK to tabPackButton,
            VehicleInfoTab.TOOL to tabToolButton,
            VehicleInfoTab.WORKFLOW to tabWorkflowButton
        ).forEach { (tab, button) ->
            val active = tab == selected
            button.background = if (active) activeBg else defaultBg
            button.setTextColor(if (active) activeColor else defaultColor)
        }
    }

    private fun buildContent(snapshot: VehicleFeatureSnapshot, tab: VehicleInfoTab): String = when (tab) {
        VehicleInfoTab.DOMAIN -> snapshot.domains.joinToString("\n") {
            "${it.code} ${it.label}"
        }
        VehicleInfoTab.PACK -> snapshot.packs.joinToString("\n") {
            "${it.packId} ${it.name}（领域 ${it.domainCode} · ${it.priority}）"
        }
        VehicleInfoTab.TOOL -> snapshot.tools.joinToString("\n") {
            "${it.toolId} ${it.name}（${it.domainCode} · ${it.packId} · ${it.operationType}）"
        }
        VehicleInfoTab.WORKFLOW -> snapshot.workflows.joinToString("\n\n") {
            buildString {
                append("${it.workflowId} ${it.name}（领域 ${it.ownerDomainCode}）\n")
                append("  步骤: ${it.stepToolIds.joinToString(" → ")}\n")
                append("  失败策略: ${it.failurePolicy}")
            }
        }
    }
}
