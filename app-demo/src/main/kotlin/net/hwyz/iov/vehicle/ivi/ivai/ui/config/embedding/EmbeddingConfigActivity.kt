package net.hwyz.iov.vehicle.ivi.ivai.ui.config.embedding

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService

/**
 * 嵌入模型配置页（CR-011 补齐 · 仿 CR-003 ModelConfigActivity）：绑定 AgentService
 * 复用共享 RAG 配置仓库，渲染 [EmbeddingConfigUiState] 并转发
 * [EmbeddingConfigUiAction]。密钥明文绝不进入状态、日志或保存实例。
 *
 * 留空保存 = 回退本地默认嵌入（哈希桩）；配置完整后运行时切换在线 Embedding，
 * 模型/维度变化按兼容键触发索引全量重建（IVAI-REQ-102）。
 */
class EmbeddingConfigActivity : ComponentActivity() {

    private val viewModel: EmbeddingConfigViewModel by viewModels()

    private lateinit var baseUrlInput: EditText
    private lateinit var modelIdInput: EditText
    private lateinit var advancedToggleButton: Button
    private lateinit var advancedRow: View
    private lateinit var modelVersionInput: EditText
    private lateinit var dimensionInput: EditText
    private lateinit var timeoutInput: EditText
    private lateinit var retriesInput: EditText
    private lateinit var batchInput: EditText
    private lateinit var allowedHostsInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var backButton: Button
    private lateinit var toggleKeyButton: Button
    private lateinit var clearKeyCheck: CheckBox
    private lateinit var keyStatusText: TextView
    private lateinit var errorText: TextView
    private lateinit var statusText: TextView
    private lateinit var testButton: Button
    private lateinit var saveButton: Button
    private lateinit var resetButton: Button

    private var agentService: AgentService? = null
    private var lastMessage: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AgentService.LocalBinder).getService()
            agentService = service
            viewModel.attach(ServiceEmbeddingConfigGateway(service))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_embedding_config)

        baseUrlInput = findViewById(R.id.baseUrlInput)
        modelIdInput = findViewById(R.id.modelIdInput)
        advancedToggleButton = findViewById(R.id.advancedToggleButton)
        advancedRow = findViewById(R.id.advancedRow)
        modelVersionInput = findViewById(R.id.modelVersionInput)
        dimensionInput = findViewById(R.id.dimensionInput)
        timeoutInput = findViewById(R.id.timeoutInput)
        retriesInput = findViewById(R.id.retriesInput)
        batchInput = findViewById(R.id.batchInput)
        allowedHostsInput = findViewById(R.id.allowedHostsInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        backButton = findViewById(R.id.backButton)
        toggleKeyButton = findViewById(R.id.toggleKeyButton)
        clearKeyCheck = findViewById(R.id.clearKeyCheck)
        keyStatusText = findViewById(R.id.keyStatusText)
        errorText = findViewById(R.id.errorText)
        statusText = findViewById(R.id.statusText)
        testButton = findViewById(R.id.testButton)
        saveButton = findViewById(R.id.saveButton)
        resetButton = findViewById(R.id.resetButton)

        baseUrlInput.doAfterTextChanged { text ->
            val value = text?.toString() ?: ""
            if (value != viewModel.state.value.baseUrl) {
                viewModel.onAction(EmbeddingConfigUiAction.BaseUrlChanged(value))
            }
        }
        modelIdInput.doAfterTextChanged { text ->
            val value = text?.toString() ?: ""
            if (value != viewModel.state.value.modelId) {
                viewModel.onAction(EmbeddingConfigUiAction.ModelIdChanged(value))
            }
        }
        advancedToggleButton.setOnClickListener { advancedRow.toggleVisibility() }
        modelVersionInput.doAfterTextChanged { text ->
            val value = text?.toString() ?: ""
            if (value != viewModel.state.value.modelVersion) {
                viewModel.onAction(EmbeddingConfigUiAction.ModelVersionChanged(value))
            }
        }
        dimensionInput.doAfterTextChanged { text ->
            val value = text?.toString() ?: ""
            if (value != viewModel.state.value.dimension) {
                viewModel.onAction(EmbeddingConfigUiAction.DimensionChanged(value))
            }
        }
        timeoutInput.doAfterTextChanged { text ->
            val value = text?.toString() ?: ""
            if (value != viewModel.state.value.timeoutMs) {
                viewModel.onAction(EmbeddingConfigUiAction.TimeoutMsChanged(value))
            }
        }
        retriesInput.doAfterTextChanged { text ->
            val value = text?.toString() ?: ""
            if (value != viewModel.state.value.maxRetries) {
                viewModel.onAction(EmbeddingConfigUiAction.MaxRetriesChanged(value))
            }
        }
        batchInput.doAfterTextChanged { text ->
            val value = text?.toString() ?: ""
            if (value != viewModel.state.value.batchSize) {
                viewModel.onAction(EmbeddingConfigUiAction.BatchSizeChanged(value))
            }
        }
        allowedHostsInput.doAfterTextChanged { text ->
            val value = text?.toString() ?: ""
            if (value != viewModel.state.value.allowedHosts) {
                viewModel.onAction(EmbeddingConfigUiAction.AllowedHostsChanged(value))
            }
        }
        apiKeyInput.doAfterTextChanged { text ->
            val value = text?.toString() ?: ""
            if (value != viewModel.state.value.apiKeyDraft) {
                viewModel.onAction(EmbeddingConfigUiAction.ApiKeyChanged(value))
            }
        }
        toggleKeyButton.setOnClickListener {
            viewModel.onAction(EmbeddingConfigUiAction.ToggleApiKeyVisibility)
        }
        clearKeyCheck.setOnClickListener {
            viewModel.onAction(EmbeddingConfigUiAction.ToggleClearKeyRequested)
        }
        testButton.setOnClickListener { viewModel.onAction(EmbeddingConfigUiAction.TestConnectionClicked) }
        saveButton.setOnClickListener { viewModel.onAction(EmbeddingConfigUiAction.SaveClicked) }
        resetButton.setOnClickListener { viewModel.requestReset() }
        backButton.setOnClickListener { handleBack() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.resetRequests.collect { showResetConfirmDialog() } }
            }
        }
    }

    private fun View.toggleVisibility() {
        visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE
        advancedToggleButton.text =
            if (visibility == View.VISIBLE) "收起高级选项" else "高级选项（模型版本 / 白名单）"
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, AgentService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        unbindService(serviceConnection)
        agentService = null
        super.onStop()
    }

    override fun onBackPressed() {
        handleBack()
    }

    /** 返回前先确认未保存的修改。 */
    private fun handleBack() {
        if (viewModel.state.value.hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("未保存的修改")
                .setMessage("当前修改尚未保存，确定离开吗？")
                .setPositiveButton("离开") { _, _ -> finish() }
                .setNegativeButton("继续编辑", null)
                .show()
        } else {
            finish()
        }
    }

    private fun showResetConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("恢复默认配置")
            .setMessage("将清空在线嵌入配置并清除已保存密钥（回退本地嵌入），确定继续吗？")
            .setPositiveButton("确定") { _, _ -> viewModel.confirmReset() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun render(state: EmbeddingConfigUiState) {
        if (baseUrlInput.text.toString() != state.baseUrl) baseUrlInput.setText(state.baseUrl)
        if (modelIdInput.text.toString() != state.modelId) modelIdInput.setText(state.modelId)
        advancedToggleButton.text =
            if (advancedRow.visibility == View.VISIBLE) "收起高级选项" else "高级选项（模型版本 / 白名单）"
        if (modelVersionInput.text.toString() != state.modelVersion) modelVersionInput.setText(state.modelVersion)
        if (dimensionInput.text.toString() != state.dimension) dimensionInput.setText(state.dimension)
        if (timeoutInput.text.toString() != state.timeoutMs) timeoutInput.setText(state.timeoutMs)
        if (retriesInput.text.toString() != state.maxRetries) retriesInput.setText(state.maxRetries)
        if (batchInput.text.toString() != state.batchSize) batchInput.setText(state.batchSize)
        if (allowedHostsInput.text.toString() != state.allowedHosts) allowedHostsInput.setText(state.allowedHosts)
        if (apiKeyInput.text.toString() != state.apiKeyDraft) apiKeyInput.setText(state.apiKeyDraft)

        apiKeyInput.transformationMethod =
            if (state.showApiKey) null else PasswordTransformationMethod.getInstance()
        apiKeyInput.inputType = if (state.showApiKey) {
            InputType.TYPE_CLASS_TEXT
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        toggleKeyButton.text = if (state.showApiKey) "隐藏" else "显示"

        clearKeyCheck.isChecked = state.clearKeyRequested
        clearKeyCheck.isEnabled = state.keyStatus == KeyStatus.SET && !state.isSaving

        keyStatusText.text = when (state.keyStatus) {
            KeyStatus.SET -> "已保存密钥：已设置（不回显明文）"
            KeyStatus.NOT_SET -> "已保存密钥：未设置"
            KeyStatus.INVALID -> "已保存密钥：无效，请重新设置或恢复默认"
        }

        val busy = state.isTesting || state.isSaving
        testButton.isEnabled = !busy
        saveButton.isEnabled = !busy
        resetButton.isEnabled = !state.isSaving
        testButton.text = if (state.isTesting) "测试中…" else "测试连接"
        saveButton.text = if (state.isSaving) "保存中…" else "保存"

        if (state.validationErrors.isEmpty()) {
            errorText.visibility = View.GONE
        } else {
            errorText.visibility = View.VISIBLE
            errorText.text = state.validationErrors.values.joinToString("\n")
        }

        statusText.text = state.message ?: ""
        statusText.setTextColor(
            resources.getColor(
                if (state.message?.startsWith("连接测试成功") == true ||
                    state.message?.startsWith("保存成功") == true ||
                    state.message?.startsWith("已恢复默认") == true
                ) {
                    R.color.status_ok
                } else {
                    R.color.text_secondary
                },
                null
            )
        )

        if (state.message != null && state.message != lastMessage) {
            lastMessage = state.message
            Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
        }
    }
}
