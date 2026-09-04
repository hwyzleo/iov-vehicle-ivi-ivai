package net.hwyz.iov.vehicle.ivi.ivai.ui.config

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
 * LLM model config screen (IVI-IVAI-DSN-CR-003 / IVAI-REQ-023~029).
 * Binds AgentService to reuse its shared config repository, renders
 * [ModelConfigUiState] and forwards [ModelConfigUiAction]s to the ViewModel.
 * The raw key never appears in state, logs or saved instances.
 */
class ModelConfigActivity : ComponentActivity() {

    private val viewModel: ModelConfigViewModel by viewModels()

    private lateinit var baseUrlInput: EditText
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
            viewModel.attach(ServiceModelConfigGateway(service.configRepository))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            viewModel.detach()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_config)

        baseUrlInput = findViewById(R.id.baseUrlInput)
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
                viewModel.onAction(ModelConfigUiAction.BaseUrlChanged(value))
            }
        }
        backButton.setOnClickListener { handleBack() }
        apiKeyInput.doAfterTextChanged { text ->
            val value = text?.toString() ?: ""
            if (value != viewModel.state.value.apiKeyDraft) {
                viewModel.onAction(ModelConfigUiAction.ApiKeyChanged(value))
            }
        }
        toggleKeyButton.setOnClickListener {
            viewModel.onAction(ModelConfigUiAction.ToggleApiKeyVisibility)
        }
        clearKeyCheck.setOnClickListener {
            viewModel.onAction(ModelConfigUiAction.ToggleClearKeyRequested)
        }
        testButton.setOnClickListener { viewModel.onAction(ModelConfigUiAction.TestConnectionClicked) }
        saveButton.setOnClickListener { viewModel.onAction(ModelConfigUiAction.SaveClicked) }
        resetButton.setOnClickListener { viewModel.requestReset() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.resetRequests.collect { showResetConfirmDialog() } }
            }
        }
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
        viewModel.detach()
        super.onStop()
    }

    override fun onBackPressed() {
        handleBack()
    }

    /** Returns to the chat screen, confirming first when there are unsaved edits. */
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
            .setMessage("将恢复默认模型地址并清除已保存的密钥，确定继续吗？")
            .setPositiveButton("确定") { _, _ -> viewModel.confirmReset() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun render(state: ModelConfigUiState) {
        if (baseUrlInput.text.toString() != state.baseUrl) {
            baseUrlInput.setText(state.baseUrl)
        }
        if (apiKeyInput.text.toString() != state.apiKeyDraft) {
            apiKeyInput.setText(state.apiKeyDraft)
        }
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

        // Guarantee the user always sees the outcome, even if statusText is off-screen.
        if (state.message != null && state.message != lastMessage) {
            lastMessage = state.message
            Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
        }
    }
}
