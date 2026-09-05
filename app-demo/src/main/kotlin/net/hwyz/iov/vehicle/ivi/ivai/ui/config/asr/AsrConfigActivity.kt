package net.hwyz.iov.vehicle.ivi.ivai.ui.config.asr

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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
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
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrFallbackPolicy
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrProviderType

/**
 * ASR config screen (IVI-IVAI-DSN-CR-006 / IVAI-REQ-051~054). Binds AgentService
 * to reuse its shared ASR config repository, renders [AsrConfigUiState] and
 * forwards [AsrConfigUiAction]s to the ViewModel.
 *
 * - Android providers hide service address / model / API key; remote providers
 *   show them plus "测试连接".
 * - The raw key never appears in state, logs or saved instances.
 * - Test / save are mutually exclusive; save and reset never corrupt the
 *   currently effective config (repository guarantee).
 */
class AsrConfigActivity : ComponentActivity() {

    private val viewModel: AsrConfigViewModel by viewModels()

    private lateinit var providerSpinner: Spinner
    private lateinit var remoteFields: View
    private lateinit var baseUrlInput: EditText
    private lateinit var modelNameInput: EditText
    private lateinit var keyStatusText: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var toggleKeyButton: Button
    private lateinit var clearKeyCheck: CheckBox
    private lateinit var languageInput: EditText
    private lateinit var preferOfflineCheck: CheckBox
    private lateinit var connectTimeoutInput: EditText
    private lateinit var recognitionTimeoutInput: EditText
    private lateinit var fallbackSpinner: Spinner
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
            viewModel.attach(ServiceAsrConfigGateway(service))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            viewModel.detach()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asr_config)

        providerSpinner = findViewById(R.id.asrProviderSpinner)
        remoteFields = findViewById(R.id.remoteFields)
        baseUrlInput = findViewById(R.id.asrBaseUrlInput)
        modelNameInput = findViewById(R.id.asrModelNameInput)
        keyStatusText = findViewById(R.id.asrKeyStatusText)
        apiKeyInput = findViewById(R.id.asrApiKeyInput)
        toggleKeyButton = findViewById(R.id.asrToggleKeyButton)
        clearKeyCheck = findViewById(R.id.asrClearKeyCheck)
        languageInput = findViewById(R.id.asrLanguageInput)
        preferOfflineCheck = findViewById(R.id.asrPreferOfflineCheck)
        connectTimeoutInput = findViewById(R.id.asrConnectTimeoutInput)
        recognitionTimeoutInput = findViewById(R.id.asrRecognitionTimeoutInput)
        fallbackSpinner = findViewById(R.id.asrFallbackSpinner)
        errorText = findViewById(R.id.asrErrorText)
        statusText = findViewById(R.id.asrStatusText)
        testButton = findViewById(R.id.asrTestButton)
        saveButton = findViewById(R.id.asrSaveButton)
        resetButton = findViewById(R.id.asrResetButton)

        providerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            AsrProviderType.entries.map { PROVIDER_LABELS.getValue(it) }
        )
        providerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = AsrProviderType.entries[position]
                if (selected != viewModel.state.value.providerType) {
                    viewModel.onAction(AsrConfigUiAction.ProviderTypeChanged(selected))
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        fallbackSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            AsrFallbackPolicy.entries.map { FALLBACK_LABELS.getValue(it) }
        )
        fallbackSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = AsrFallbackPolicy.entries[position]
                if (selected != viewModel.state.value.fallbackPolicy) {
                    viewModel.onAction(AsrConfigUiAction.FallbackPolicyChanged(selected))
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        baseUrlInput.doAfterTextChanged { t ->
            val v = t?.toString() ?: ""
            if (v != viewModel.state.value.baseUrl) viewModel.onAction(AsrConfigUiAction.BaseUrlChanged(v))
        }
        modelNameInput.doAfterTextChanged { t ->
            val v = t?.toString() ?: ""
            if (v != viewModel.state.value.modelName) viewModel.onAction(AsrConfigUiAction.ModelNameChanged(v))
        }
        languageInput.doAfterTextChanged { t ->
            val v = t?.toString() ?: ""
            if (v != viewModel.state.value.languageTag) viewModel.onAction(AsrConfigUiAction.LanguageChanged(v))
        }
        connectTimeoutInput.doAfterTextChanged { t ->
            val v = t?.toString() ?: ""
            if (v != viewModel.state.value.connectTimeoutMs) viewModel.onAction(AsrConfigUiAction.ConnectTimeoutChanged(v))
        }
        recognitionTimeoutInput.doAfterTextChanged { t ->
            val v = t?.toString() ?: ""
            if (v != viewModel.state.value.recognitionTimeoutMs) viewModel.onAction(AsrConfigUiAction.RecognitionTimeoutChanged(v))
        }
        preferOfflineCheck.setOnClickListener {
            viewModel.onAction(AsrConfigUiAction.PreferOfflineChanged(preferOfflineCheck.isChecked))
        }
        apiKeyInput.doAfterTextChanged { t ->
            val v = t?.toString() ?: ""
            if (v != viewModel.state.value.apiKeyDraft) viewModel.onAction(AsrConfigUiAction.ApiKeyChanged(v))
        }
        toggleKeyButton.setOnClickListener { viewModel.onAction(AsrConfigUiAction.ToggleApiKeyVisibility) }
        clearKeyCheck.setOnClickListener { viewModel.onAction(AsrConfigUiAction.ToggleClearKeyRequested) }
        testButton.setOnClickListener { viewModel.onAction(AsrConfigUiAction.TestConnectionClicked) }
        saveButton.setOnClickListener { viewModel.onAction(AsrConfigUiAction.SaveClicked) }
        resetButton.setOnClickListener { viewModel.requestReset() }
        findViewById<Button>(R.id.backButton).setOnClickListener { onBackPressed() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.resetRequests.collect { showResetConfirmDialog() } }
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
        viewModel.detach()
        super.onStop()
    }

    override fun onBackPressed() {
        if (viewModel.state.value.hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("未保存的修改")
                .setMessage("当前修改尚未保存，确定离开吗？")
                .setPositiveButton("离开") { _, _ -> finish() }
                .setNegativeButton("继续编辑", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    private fun showResetConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("恢复默认配置")
            .setMessage("将恢复默认 ASR 配置并清除已保存的密钥，确定继续吗？")
            .setPositiveButton("确定") { _, _ -> viewModel.confirmReset() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun render(state: AsrConfigUiState) {
        if (providerSpinner.selectedItemPosition != AsrProviderType.entries.indexOf(state.providerType)) {
            providerSpinner.setSelection(AsrProviderType.entries.indexOf(state.providerType))
        }
        val isRemote = state.providerType == AsrProviderType.HTTP_COMPATIBLE ||
            state.providerType == AsrProviderType.VENDOR
        remoteFields.visibility = if (isRemote) View.VISIBLE else View.GONE
        // Android providers hide the remote-connectivity test; they need no network.
        testButton.visibility = if (isRemote) View.VISIBLE else View.GONE
        // 优先离线 is an Android-provider preference; connect timeout is remote-only.
        preferOfflineCheck.visibility = if (isRemote) View.GONE else View.VISIBLE
        findViewById<View>(R.id.asrConnectTimeoutInput).visibility =
            if (isRemote) View.VISIBLE else View.GONE

        if (baseUrlInput.text.toString() != state.baseUrl) baseUrlInput.setText(state.baseUrl)
        if (modelNameInput.text.toString() != state.modelName) modelNameInput.setText(state.modelName)
        if (languageInput.text.toString() != state.languageTag) languageInput.setText(state.languageTag)
        if (connectTimeoutInput.text.toString() != state.connectTimeoutMs) connectTimeoutInput.setText(state.connectTimeoutMs)
        if (recognitionTimeoutInput.text.toString() != state.recognitionTimeoutMs) recognitionTimeoutInput.setText(state.recognitionTimeoutMs)
        preferOfflineCheck.isChecked = state.preferOffline
        if (fallbackSpinner.selectedItemPosition != AsrFallbackPolicy.entries.indexOf(state.fallbackPolicy)) {
            fallbackSpinner.setSelection(AsrFallbackPolicy.entries.indexOf(state.fallbackPolicy))
        }
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

    private companion object {
        val PROVIDER_LABELS = mapOf(
            AsrProviderType.ANDROID_ON_DEVICE to "Android 端侧",
            AsrProviderType.ANDROID_SYSTEM to "Android 系统",
            AsrProviderType.HTTP_COMPATIBLE to "HTTP 兼容",
            AsrProviderType.VENDOR to "厂商引擎"
        )
        val FALLBACK_LABELS = mapOf(
            AsrFallbackPolicy.TEXT_ONLY to "仅文本输入",
            AsrFallbackPolicy.ON_DEVICE_TO_SYSTEM to "端侧 → 系统",
            AsrFallbackPolicy.ON_DEVICE_TO_REMOTE to "端侧 → 远程",
            AsrFallbackPolicy.SYSTEM_TO_REMOTE to "系统 → 远程"
        )
    }
}
