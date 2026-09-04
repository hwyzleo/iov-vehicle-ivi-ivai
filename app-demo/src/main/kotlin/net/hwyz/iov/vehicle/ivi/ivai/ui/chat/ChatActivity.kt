package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import net.hwyz.iov.vehicle.ivi.ivai.ui.config.ModelConfigActivity

/**
 * Classic Android-View chatbot screen (IVI-IVAI-DSN-CR-002).
 * Owns no agent state: it only binds the service, forwards [ChatUiAction]s to
 * the ViewModel and renders [ChatUiState] into the RecyclerView.
 */
class ChatActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private lateinit var messageList: RecyclerView
    private lateinit var inputEdit: EditText
    private lateinit var adapter: ChatMessageAdapter

    private var agentService: AgentService? = null
    private var lastMessages: List<ChatMessage>? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AgentService.LocalBinder).getService()
            agentService = service
            viewModel.attach(ServiceChatAgentGateway(service))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            viewModel.detach()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        messageList = findViewById(R.id.messageList)
        inputEdit = findViewById(R.id.chatInput)
        val sendButton: Button = findViewById(R.id.sendButton)

        adapter = ChatMessageAdapter(
            onConfirm = { id -> viewModel.onAction(ChatUiAction.ConfirmClicked(id)) },
            onCancel = { id -> viewModel.onAction(ChatUiAction.CancelClicked(id)) },
            onRetry = { id -> viewModel.onAction(ChatUiAction.RetryClicked(id)) }
        )
        messageList.layoutManager = LinearLayoutManager(this)
        messageList.adapter = adapter

        sendButton.setOnClickListener { sendFromInput() }
        findViewById<Button>(R.id.configButton).setOnClickListener {
            startActivity(Intent(this, ModelConfigActivity::class.java))
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { render(it) }
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
        unbindService(serviceConnection)
        agentService = null
        viewModel.detach()
        super.onStop()
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
