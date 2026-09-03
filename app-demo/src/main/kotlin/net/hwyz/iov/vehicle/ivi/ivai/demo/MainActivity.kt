package net.hwyz.iov.vehicle.ivi.ivai.demo

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.agent.workflow.AgentResult
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService

/**
 * Debug console: input test statement → AgentService → Mac Ollama → Mock tool → result.
 * app-demo only renders; it never owns core task state (IVI-IVAI-DSN-CR-001).
 */
class MainActivity : Activity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var agentService: AgentService? = null

    private lateinit var inputEdit: EditText
    private lateinit var logView: TextView

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            agentService = (binder as AgentService.LocalBinder).getService()
            appendLog("服务已连接 session=${agentService?.sessionId()}")
            appendLog("网络：${agentService?.isNetworkAvailable()}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            appendLog("服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        val confirmButton = findViewById<Button>(Ids.CONFIRM)
        confirmButton.setOnClickListener { send("确认") }
        findViewById<Button>(Ids.SEND).setOnClickListener {
            val text = inputEdit.text.toString().trim()
            if (text.isNotEmpty()) send(text)
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

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun send(text: String) {
        val service = agentService
        if (service == null) {
            appendLog("服务未连接")
            return
        }
        appendLog("> $text")
        uiScope.launch {
            try {
                val result = service.submit(text).await()
                render(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("!! 异常：${e.message}")
            }
        }
    }

    private fun render(result: AgentResult) {
        appendLog("== 结果 ==")
        appendLog("状态：${result.state}  路由：${result.route}")
        result.errorCode?.let { appendLog("错误码：$it") }
        result.validationIssues.forEach { appendLog("校验：${it.message}") }
        appendLog("回复：${result.responseText}")
        if (result.replayed) appendLog("（幂等重放，未重复执行）")
        result.executionResult?.let {
            appendLog("执行：${it.toolId} [${it.status}] ${it.message}")
            if (it.stateChanges.isNotEmpty()) appendLog("状态变化：${it.stateChanges}")
        }
        result.rawModelContent?.let {
            appendLog("--- 原始模型输出 ---")
            appendLog(it)
        }
        appendLog("延迟：模型=${result.telemetry.modelLatencyMs}ms 总=${result.telemetry.totalLatencyMs}ms")
        appendLog("车辆状态：${agentService?.vehicleState()?.snapshotValues()}")
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            logView.append(line + "\n")
            logView.post { (logView.parent as? ScrollView)?.fullScroll(ViewGroup.FOCUS_DOWN) }
        }
    }

    private fun buildUi(): ScrollView {
        val root = ScrollView(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(container, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val title = TextView(this).apply {
            text = "IVI-IVAI Demo · Android → Mac Ollama(qwen3.5:4b) → Mock"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        container.addView(title)

        inputEdit = EditText(this).apply {
            hint = "输入测试语句，例如：打开空调 / 我有点冷 / 温度调到"
            textSize = 16f
        }
        container.addView(inputEdit, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        container.addView(buttons)

        val send = Button(this).apply {
            text = "发送"
            id = Ids.SEND
        }
        buttons.addView(send, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val confirm = Button(this).apply {
            text = "确认"
            id = Ids.CONFIRM
        }
        buttons.addView(confirm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.START
            setTextIsSelectable(true)
        }
        container.addView(logView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        return root
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private object Ids {
        const val SEND = 0x1
        const val CONFIRM = 0x2
    }
}
