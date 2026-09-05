package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.demo.R
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability
import net.hwyz.iov.vehicle.ivi.ivai.ui.config.ModelConfigActivity
import net.hwyz.iov.vehicle.ivi.ivai.ui.settings.SettingsActivity

/**
 * Classic Android-View chatbot screen (IVI-IVAI-DSN-CR-002 + CR-006).
 * Owns no agent state: it binds the service, forwards [ChatUiAction]s to the
 * ViewModel and renders [ChatUiState] into the RecyclerView.
 *
 * Push-to-talk (CR-006): the voice button requests RECORD_AUDIO via the
 * Activity Result API, forwards ACTION_DOWN / ACTION_UP / ACTION_CANCEL to the
 * ViewModel's [VoiceInputController], and renders [VoiceInputState] through
 * [VoiceInputUiBinder]. Text input always remains as the fallback.
 */
class ChatActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private lateinit var messageList: RecyclerView
    private lateinit var inputEdit: EditText
    private lateinit var adapter: ChatMessageAdapter
    private lateinit var voiceButton: Button
    private lateinit var voiceHint: TextView
    private lateinit var voiceBinder: VoiceInputUiBinder

    private var agentService: AgentService? = null
    private var lastMessages: List<ChatMessage>? = null

    /** Voice entry enabled only when permission + a usable Recognition Service exist. */
    private var voiceAvailable = false
    private var pendingVoiceStart = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AgentService.LocalBinder).getService()
            agentService = service
            viewModel.attach(ServiceChatAgentGateway(service), ServiceVoiceInputGateway(service))
            refreshVoiceAvailability()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            viewModel.detach()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingVoiceStart = false
        refreshVoiceAvailability()
        if (granted) {
            // 授权后重新走完整判断：无识别服务时提示原因，可用时启动会话。
            handleVoiceDown()
        } else {
            Toast.makeText(
                this,
                "需要麦克风权限才能使用语音输入，可前往设置开启",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        messageList = findViewById(R.id.messageList)
        inputEdit = findViewById(R.id.chatInput)
        val sendButton: Button = findViewById(R.id.sendButton)
        voiceButton = findViewById(R.id.voiceButton)
        voiceHint = findViewById(R.id.voiceHint)
        voiceBinder = VoiceInputUiBinder(voiceButton, voiceHint)

        adapter = ChatMessageAdapter(
            onConfirm = { id -> viewModel.onAction(ChatUiAction.ConfirmClicked(id)) },
            onCancel = { id -> viewModel.onAction(ChatUiAction.CancelClicked(id)) },
            onRetry = { id -> viewModel.onAction(ChatUiAction.RetryClicked(id)) },
            onToggleDetail = { id -> viewModel.onAction(ChatUiAction.TogglePerformanceDetails(id)) }
        )
        messageList.layoutManager = LinearLayoutManager(this)
        messageList.adapter = adapter

        sendButton.setOnClickListener { sendFromInput() }
        findViewById<Button>(R.id.configButton).setOnClickListener {
            startActivity(Intent(this, ModelConfigActivity::class.java))
        }
        findViewById<Button>(R.id.debugButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        inputEdit.doAfterTextChanged { editable ->
            viewModel.onAction(ChatUiAction.InputChanged(editable?.toString() ?: ""))
        }
        inputEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendFromInput()
                true
            } else {
                false
            }
        }

        // Push-to-talk gestures (CR-006): down → start, up in-bounds → stop,
        // up out-of-bounds / ACTION_CANCEL → cancel (no submission).
        voiceButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handleVoiceDown()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isInside(voiceButton, event)) {
                        viewModel.onAction(ChatUiAction.VoiceButtonUp)
                    } else {
                        viewModel.onAction(ChatUiAction.VoiceButtonCancel)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    viewModel.onAction(ChatUiAction.VoiceButtonCancel)
                    true
                }
                else -> false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { render(it) }
                }
                launch {
                    viewModel.voiceState.collect { voiceState ->
                        voiceBinder.bind(voiceState, voiceAvailable)
                    }
                }
                launch {
                    viewModel.hints.collect { hint ->
                        Toast.makeText(this@ChatActivity, hint, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, AgentService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        // Release the mic when the page goes to background (CR-006 lifecycle).
        viewModel.cancelVoiceInput()
        unbindService(serviceConnection)
        agentService = null
        viewModel.detach()
        super.onStop()
    }

    private fun handleVoiceDown() {
        when {
            !hasMicPermission() -> {
                pendingVoiceStart = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            // 有权限但设备/配置不可用（无 Recognition Service，或端侧不可用且降级策略为 TEXT_ONLY）
            !voiceAvailable -> {
                Toast.makeText(
                    this,
                    "当前设备不支持语音识别，请检查识别服务或改用文本输入",
                    Toast.LENGTH_SHORT
                ).show()
            }
            else -> viewModel.onAction(ChatUiAction.VoiceButtonDown)
        }
    }

    /** Re-checks permission + Recognition Service and notifies the ViewModel. */
    private fun refreshVoiceAvailability() {
        val hasPermission = hasMicPermission()
        val capability = if (hasPermission) {
            agentService?.asrCapability() ?: SpeechCapability.UNAVAILABLE
        } else {
            SpeechCapability.UNAVAILABLE
        }
        voiceAvailable = capability != SpeechCapability.UNAVAILABLE
        viewModel.onVoiceCapabilityChanged(capability)
        voiceBinder.bind(viewModel.voiceState.value, voiceAvailable)
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isInside(view: View, event: MotionEvent): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = location[0]
        val y = location[1]
        return event.rawX in x.toFloat()..(x + view.width).toFloat() &&
            event.rawY in y.toFloat()..(y + view.height).toFloat()
    }

    private fun sendFromInput() {
        // Clear the input box only after the ViewModel accepted the submit
        // (IVAI-REQ-013: 发送后输入框清空). submitFromInput reads state.inputText
        // synchronously first, so clearing afterwards can never wipe the text.
        if (viewModel.submitFromInput()) {
            inputEdit.setText("")
        }
    }

    private fun render(state: ChatUiState) {
        val messagesChanged = lastMessages != state.messages
        adapter.submitList(state.messages)
        if (messagesChanged && state.messages.isNotEmpty()) {
            // New message / status change → scroll to the latest (IVAI-REQ-020).
            messageList.scrollToPosition(state.messages.size - 1)
        }
        lastMessages = state.messages
    }
}
