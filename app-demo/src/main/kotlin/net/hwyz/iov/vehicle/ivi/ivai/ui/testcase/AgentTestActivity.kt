package net.hwyz.iov.vehicle.ivi.ivai.ui.testcase

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway.ServiceAgentCommandGateway
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AgentTestCaseRepository
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository.AssetsAgentTestCaseLoader
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService

/**
 * 本地 Agent 回归测试用例页（IVI-IVAI-DSN-CR-012）。
 *
 * 经典 Android View：Toolbar（返回 / 测试用例 / Suite 版本）、Summary、Action
 * 按钮、RecyclerView。列表默认展示概要，点击单条展开预期/实际差异；运行中自动
 * 滚动到当前用例，用户手动滚动后不强制抢回位置。
 *
 * 不持有任何 Agent 状态：绑定服务后把 [ServiceAgentCommandGateway] 与资产仓库
 * 交给 [AgentTestViewModel]。测试页不直接依赖 DomainRouter / Retriever /
 * ModelProvider / ToolExecutor / Adapter。
 */
class AgentTestActivity : ComponentActivity() {

    private val viewModel: AgentTestViewModel by viewModels()

    private lateinit var caseList: RecyclerView
    private lateinit var adapter: AgentTestCaseAdapter
    private lateinit var summaryText: TextView
    private lateinit var startButton: Button
    private lateinit var suiteVersionText: TextView

    private var agentService: AgentService? = null
    private var userScrolled = false
    private var lastActiveCaseId: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AgentService.LocalBinder).getService()
            agentService = service
            viewModel.attach(
                gateway = ServiceAgentCommandGateway(service, service),
                repository = AgentTestCaseRepository(
                    AssetsAgentTestCaseLoader(this@AgentTestActivity)
                )
            )
            viewModel.load()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_test)

        caseList = findViewById(R.id.caseList)
        summaryText = findViewById(R.id.summaryText)
        startButton = findViewById(R.id.startButton)
        suiteVersionText = findViewById(R.id.suiteVersionText)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        startButton.setOnClickListener {
            // 运行中按钮切换为「取消」；其余状态为「开始」。
            if (viewModel.state.value.runState == TestRunState.RUNNING) {
                viewModel.cancel()
            } else {
                viewModel.start()
            }
        }

        adapter = AgentTestCaseAdapter(onToggleExpand = viewModel::toggleExpand)
        caseList.layoutManager = LinearLayoutManager(this)
        caseList.adapter = adapter
        // 用户手动滚动后不强制抢回位置（CR-012 UI 设计）。
        caseList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) userScrolled = true
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startService(Intent(this, AgentService::class.java))
        bindService(Intent(this, AgentService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        unbindService(serviceConnection)
        agentService = null
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            stopService(Intent(this, AgentService::class.java))
        }
        super.onDestroy()
    }

    private fun render(state: AgentTestUiState) {
        summaryText.text = summaryOf(state)
        suiteVersionText.text = state.suiteVersion?.let { "v: $it" } ?: ""
        val busy = state.runState == TestRunState.RUNNING ||
            state.runState == TestRunState.CANCELLING ||
            state.runState == TestRunState.LOADING
        startButton.isEnabled = !busy
        startButton.text = when (state.runState) {
            TestRunState.RUNNING -> "取消"
            TestRunState.CANCELLING -> "取消中…"
            else -> "开始"
        }
        adapter.submitList(state.cases)

        // 运行中自动滚动到当前用例，但用户手动滚动后不强制抢回位置。
        val active = state.activeCaseId
        if (active != null && active != lastActiveCaseId && !userScrolled) {
            val index = state.cases.indexOfFirst { it.caseId == active }
            if (index >= 0) caseList.scrollToPosition(index)
        }
        lastActiveCaseId = active
    }

    private fun summaryOf(state: AgentTestUiState): String {
        val summary = state.summary
        if (summary != null) {
            val rate = (summary.scoreRate * 100).toInt()
            return "已完成 ${summary.executed} 条 · 得分 ${summary.score}/${summary.maxScore} · $rate%" +
                "（通过 ${summary.passed} / 失败 ${summary.failed} / 跳过 ${summary.skipped}）"
        }
        return when (state.runState) {
            TestRunState.LOADING -> "加载中…"
            TestRunState.CANCELLED -> "已取消"
            else -> "未运行"
        }
    }
}
