package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentExecutionPath
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.TurnDebugInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentCommand
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentInputSource
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability
import net.hwyz.iov.vehicle.ivi.ivai.speech.observability.SpeechRecognitionMetricsRecorder
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus

/**
 * Chatbot ViewModel (IVI-IVAI-DSN-CR-002 + CR-004).
 *
 * Depends ONLY on [ChatAgentGateway], which extends the stable [AiAgentClient]
 * service contract — it never touches ModelProvider, ToolExecutor or a vehicle
 * Adapter. It projects [AgentEvent]s into [ChatUiState], keeps the conversation
 * across configuration changes and owns the per-message performance-panel
 * expansion state (default collapsed, toggled by messageId).
 *
 * Business state (Session, PendingTask, PendingConfirmation) lives in
 * agent-core / service-ai-agent; this class only maps events and enforces the
 * UI-level single-turn rules.
 */
class ChatViewModel : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** One-shot user hints ("上一条请求正在处理…") shown as toasts by the Activity. */
    private val _hints = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val hints: SharedFlow<String> = _hints.asSharedFlow()

    private var gateway: ChatAgentGateway? = null
    private var eventJob: Job? = null

    // --- Push-to-talk (IVI-IVAI-DSN-CR-006) ---

    private var voiceGateway: VoiceInputGateway? = null
    private var voiceController: VoiceInputController? = null

    /** Voice gesture state projected for the UI (listening / finalizing / error…). */
    private val _voiceState = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val voiceState: StateFlow<VoiceInputState> = _voiceState.asStateFlow()

    /**
     * Connects (or reconnects) to a gateway. Keeps the single event collection
     * job across Activity rebinds so buffered events are not re-delivered;
     * resets the projection when the service restarted with a new session.
     */
    fun attach(newGateway: ChatAgentGateway, voiceInput: VoiceInputGateway? = null) {
        val newSessionId = newGateway.sessionId()
        val current = _state.value
        when {
            current.sessionId.isEmpty() -> {
                _state.update { it.copy(sessionId = newSessionId) }
            }
            current.sessionId != newSessionId -> {
                _state.value = ChatUiState(sessionId = newSessionId)
                eventJob?.cancel()
                eventJob = null
            }
        }
        gateway = newGateway
        voiceGateway = voiceInput
        if (voiceController == null && voiceInput != null) {
            voiceController = VoiceInputController(
                scope = viewModelScope,
                deps = object : VoiceInputDeps {
                    override suspend fun createEngine(): VoiceEngineSession? =
                        voiceGateway?.createVoiceSession()

                    override suspend fun submitText(voiceSessionId: String, text: String): Boolean =
                        submitVoiceText(voiceSessionId, text)
                },
                // ASR time is an input-stage metric, never merged into Agent endToEndMs.
                metricsRecorder = SpeechRecognitionMetricsRecorder { metrics ->
                    android.util.Log.d("IVI-IVAI", "asr metrics voiceSession=${metrics.voiceSessionId} " +
                        "engine=${metrics.engineType} onDevice=${metrics.onDevice} " +
                        "prepareMs=${metrics.prepareMs} listeningMs=${metrics.listeningMs} " +
                        "finalizingMs=${metrics.finalizingMs} resultLen=${metrics.resultLength} " +
                        "error=${metrics.errorCode}")
                }
            )
            viewModelScope.launch {
                voiceController?.state?.collect { _voiceState.value = it }
            }
        }
        if (eventJob == null) {
            eventJob = viewModelScope.launch {
                newGateway.observeEvents(newSessionId).collect { onAgentEvent(it) }
            }
        }
    }

    /** Called when the service is unbound; the event job stays alive for rebind. */
    fun detach() {
        gateway = null
    }

    fun onAction(action: ChatUiAction) {
        when (action) {
            is ChatUiAction.InputChanged ->
                _state.update { it.copy(inputText = action.text) }
            ChatUiAction.SendClicked -> submitFromInput()
            is ChatUiAction.ConfirmClicked -> confirm(action.confirmationId)
            is ChatUiAction.CancelClicked -> cancel(action.confirmationId)
            is ChatUiAction.RetryClicked -> retry(action.messageId)
            is ChatUiAction.TogglePerformanceDetails -> togglePerformance(action.messageId)
            ChatUiAction.VoiceButtonDown -> startVoiceInput()
            ChatUiAction.VoiceButtonUp -> stopVoiceInput()
            ChatUiAction.VoiceButtonCancel -> cancelVoiceInput()
        }
    }

    /**
     * Sends the current input and returns whether the turn was accepted (input
     * non-empty, service connected, not busy, no pending confirmation). The
     * Activity uses this to clear the input box only after a real submit.
     */
    fun submitFromInput(): Boolean {
        val s = _state.value
        val text = s.inputText.trim()
        if (text.isEmpty()) return false
        val g = gateway ?: run {
            showHint("服务未连接，请稍候重试")
            return false
        }
        if (s.isAgentBusy) {
            showHint("上一条请求正在处理，请稍候")
            return false
        }
        if (s.pendingConfirmationId != null) {
            showHint("请先确认或取消当前操作")
            return false
        }

        val turnId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val userMessage = newMessage(
            sessionId = s.sessionId,
            turnId = turnId,
            requestId = requestId,
            role = ChatRole.USER,
            type = ChatMessageType.TEXT,
            text = text,
            status = ChatMessageStatus.SENDING
        )
        _state.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                activeTurnId = turnId,
                isAgentBusy = true
            )
        }
        viewModelScope.launch {
            val accepted = g.submit(
                AgentCommand.HandleText(
                    sessionId = s.sessionId,
                    requestId = requestId,
                    inputSource = AgentInputSource.TEXT_CHAT,
                    text = text,
                    turnId = turnId
                )
            )
            if (!accepted) {
                // Service gate rejected the turn (should not happen after the checks above).
                _state.update {
                    it.copy(
                        activeTurnId = null,
                        isAgentBusy = false,
                        messages = it.messages.map { m ->
                            if (m.messageId == userMessage.messageId) {
                                m.copy(status = ChatMessageStatus.FAILED)
                            } else m
                        }
                    )
                }
                showHint("上一条请求正在处理，请稍候")
            }
        }
        return true
    }

    private fun retry(messageId: String) {
        val s = _state.value
        val failed = s.messages.find { it.messageId == messageId } ?: return
        val original = s.messages.firstOrNull {
            it.role == ChatRole.USER && it.requestId == failed.requestId
        } ?: return showHint("无法重试：找不到原始请求")
        val g = gateway ?: return showHint("服务未连接，请稍候重试")
        if (s.isAgentBusy) return showHint("上一条请求正在处理，请稍候")
        if (s.pendingConfirmationId != null) return showHint("请先确认或取消当前操作")

        // Retry keeps the original request association via retryOfRequestId but uses a
        // fresh requestId so the idempotency guard does not return the stale failure.
        val turnId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val retryMessage = newMessage(
            sessionId = s.sessionId,
            turnId = turnId,
            requestId = requestId,
            role = ChatRole.USER,
            type = ChatMessageType.TEXT,
            text = original.text,
            status = ChatMessageStatus.SENDING,
            retryOfRequestId = original.requestId
        )
        _state.update {
            it.copy(messages = it.messages + retryMessage, activeTurnId = turnId, isAgentBusy = true)
        }
        viewModelScope.launch {
            g.submit(
                AgentCommand.HandleText(
                    sessionId = s.sessionId,
                    requestId = requestId,
                    inputSource = AgentInputSource.TEXT_CHAT,
                    text = original.text,
                    turnId = turnId
                )
            )
        }
    }

    private fun confirm(confirmationId: String) {
        val s = _state.value
        if (s.pendingConfirmationId != confirmationId) return
        val g = gateway ?: return
        // Disable the buttons immediately (idempotent UI side).
        _state.update {
            it.copy(
                pendingConfirmationId = null,
                isAgentBusy = true,
                messages = it.messages.map { m ->
                    if (m.confirmation?.confirmationId == confirmationId) {
                        m.copy(status = ChatMessageStatus.PROCESSING)
                    } else m
                }
            )
        }
        viewModelScope.launch {
            g.submit(
                AgentCommand.Confirm(
                    sessionId = s.sessionId,
                    requestId = UUID.randomUUID().toString(),
                    inputSource = AgentInputSource.TEXT_CHAT,
                    confirmationId = confirmationId
                )
            )
        }
    }

    private fun cancel(confirmationId: String) {
        val s = _state.value
        if (s.pendingConfirmationId != confirmationId) return
        val g = gateway ?: return
        _state.update {
            it.copy(
                pendingConfirmationId = null,
                isAgentBusy = true,
                messages = it.messages.map { m ->
                    if (m.confirmation?.confirmationId == confirmationId) {
                        m.copy(status = ChatMessageStatus.PROCESSING)
                    } else m
                }
            )
        }
        viewModelScope.launch {
            g.submit(
                AgentCommand.Cancel(
                    sessionId = s.sessionId,
                    requestId = UUID.randomUUID().toString(),
                    inputSource = AgentInputSource.TEXT_CHAT,
                    confirmationId = confirmationId
                )
            )
        }
    }

    /** Toggles the performance detail panel of one final message (default collapsed). */
    private fun togglePerformance(messageId: String) {
        _state.update { st ->
            st.copy(
                messages = st.messages.map { m ->
                    if (m.messageId == messageId) {
                        m.copy(isPerformanceExpanded = !m.isPerformanceExpanded)
                    } else m
                }
            )
        }
    }

    // ------------------------------------------------------------------ push-to-talk (CR-006)

    /** Re-evaluated by the Activity after binding or a permission result. */
    fun onVoiceCapabilityChanged(capability: SpeechCapability) {
        _state.update { it.copy(voiceCapability = capability) }
    }

    fun startVoiceInput() {
        val s = _state.value
        // Reuse the existing single-turn policy: no voice session while busy or
        // a confirmation is pending (design: “Agent 忙碌时遵循现有单活动 Turn 策略”).
        if (s.isAgentBusy) {
            showHint("上一条请求正在处理，请稍候")
            return
        }
        if (s.pendingConfirmationId != null) {
            showHint("请先确认或取消当前操作")
            return
        }
        voiceController?.startVoiceInput()
    }

    fun stopVoiceInput() {
        voiceController?.stopVoiceInput()
    }

    fun cancelVoiceInput() {
        voiceController?.cancelVoiceInput()
    }

    /**
     * Appends the recognized text as a normal user message (VOICE_ASR provenance
     * kept for diagnostics) and submits HandleText(VOICE_ASR). Returns false when
     * the service rejected it — the controller then silently recovers to Idle.
     */
    private suspend fun submitVoiceText(voiceSessionId: String, text: String): Boolean {
        val s = _state.value
        val g = gateway ?: run {
            showHint("服务未连接，请稍候重试")
            return false
        }
        if (s.isAgentBusy) {
            showHint("上一条请求正在处理，请稍候")
            return false
        }
        if (s.pendingConfirmationId != null) {
            showHint("请先确认或取消当前操作")
            return false
        }

        val turnId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val userMessage = newMessage(
            sessionId = s.sessionId,
            turnId = turnId,
            requestId = requestId,
            role = ChatRole.USER,
            type = ChatMessageType.TEXT,
            text = text,
            status = ChatMessageStatus.SENDING,
            inputSource = AgentInputSource.VOICE_ASR
        )
        _state.update {
            it.copy(
                messages = it.messages + userMessage,
                activeTurnId = turnId,
                isAgentBusy = true
            )
        }
        val accepted = g.submit(
            AgentCommand.HandleText(
                sessionId = s.sessionId,
                requestId = requestId,
                inputSource = AgentInputSource.VOICE_ASR,
                text = text,
                turnId = turnId
            )
        )
        if (!accepted) {
            _state.update {
                it.copy(
                    activeTurnId = null,
                    isAgentBusy = false,
                    messages = it.messages.map { m ->
                        if (m.messageId == userMessage.messageId) {
                            m.copy(status = ChatMessageStatus.FAILED)
                        } else m
                    }
                )
            }
            showHint("上一条请求正在处理，请稍候")
        }
        return accepted
    }

    override fun onCleared() {
        voiceController?.release()
        eventJob?.cancel()
        super.onCleared()
    }

    // ------------------------------------------------------------------ event → message mapping

    private fun onAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.UserSubmitted -> _state.update { st ->
                st.copy(
                    messages = st.messages.map { m ->
                        if (m.role == ChatRole.USER && m.requestId == event.requestId) {
                            m.copy(status = ChatMessageStatus.FINAL)
                        } else m
                    }
                )
            }

            is AgentEvent.ProcessingStarted -> _state.update { st ->
                st.copy(
                    activeTurnId = event.turnId,
                    isAgentBusy = true,
                    messages = st.messages + newMessage(
                        sessionId = event.sessionId,
                        turnId = event.turnId,
                        requestId = event.requestId,
                        role = ChatRole.AGENT,
                        type = ChatMessageType.PROCESSING,
                        text = event.text,
                        status = ChatMessageStatus.PROCESSING
                    )
                )
            }

            /** 流式增量：把模型原始输出逐字渲染进当前「处理中」气泡，完成后被最终结果替换。 */
            is AgentEvent.StreamingDelta -> _state.update { st ->
                st.copy(
                    messages = replaceActive(st.messages, event.turnId) { active ->
                        active.copy(
                            type = ChatMessageType.PROCESSING,
                            text = event.text,
                            status = ChatMessageStatus.PROCESSING
                        )
                    }
                )
            }

            is AgentEvent.Reply -> _state.update { st ->
                val updated = replaceActive(st.messages, event.turnId) { active ->
                    active.copy(
                        type = ChatMessageType.TEXT,
                        text = event.text,
                        status = ChatMessageStatus.FINAL,
                        confirmation = null,
                        executionTierLabel = tierLabel(event.executionPath),
                        executionPath = event.executionPath
                    )
                }
                val messages = if (updated === st.messages) {
                    updated + newMessage(
                        sessionId = event.sessionId,
                        turnId = event.turnId,
                        requestId = event.requestId,
                        role = ChatRole.AGENT,
                        type = ChatMessageType.TEXT,
                        text = event.text,
                        status = ChatMessageStatus.FINAL,
                        executionTierLabel = tierLabel(event.executionPath),
                        executionPath = event.executionPath
                    )
                } else updated
                st.copy(messages = messages, activeTurnId = null, isAgentBusy = false)
            }

            is AgentEvent.ConfirmationRequired -> _state.update { st ->
                val message = newMessage(
                    sessionId = event.sessionId,
                    turnId = event.turnId,
                    requestId = event.requestId,
                    role = ChatRole.AGENT,
                    type = ChatMessageType.CONFIRMATION,
                    text = event.text,
                    status = ChatMessageStatus.WAITING_USER,
                    confirmation = ConfirmationUiModel(
                        confirmationId = event.confirmationId,
                        toolId = event.toolId,
                        toolName = event.toolName,
                        text = event.text
                    ),
                    executionTierLabel = event.currentTier?.let { IntentTier.label(it) }
                )
                st.copy(
                    pendingConfirmationId = event.confirmationId,
                    activeTurnId = null,
                    isAgentBusy = false,
                    messages = replaceActive(st.messages, event.turnId, message)
                )
            }

            is AgentEvent.ToolExecutionStarted -> _state.update { st ->
                st.copy(
                    messages = replaceActive(st.messages, event.turnId) { active ->
                        active.copy(
                            type = ChatMessageType.PROCESSING,
                            text = event.text,
                            status = ChatMessageStatus.PROCESSING,
                            confirmation = null
                        )
                    }
                )
            }

            is AgentEvent.ToolExecutionFinished -> _state.update { st ->
                val status = when (event.status) {
                    ExecutionStatus.SUCCEEDED -> ChatMessageStatus.FINAL
                    ExecutionStatus.FAILED -> ChatMessageStatus.FAILED
                    ExecutionStatus.TIMEOUT -> ChatMessageStatus.TIMEOUT
                }
                st.copy(
                    activeTurnId = null,
                    isAgentBusy = false,
                    pendingConfirmationId = null,
                    messages = replaceActive(st.messages, event.turnId) { active ->
                        active.copy(
                            type = ChatMessageType.TOOL_RESULT,
                            text = event.message,
                            status = status,
                            retryable = event.retryable,
                            confirmation = null,
                            executionTierLabel = tierLabel(event.executionPath),
                            executionPath = event.executionPath
                        )
                    }
                )
            }

            is AgentEvent.TurnFailed -> _state.update { st ->
                st.copy(
                    activeTurnId = null,
                    isAgentBusy = false,
                    messages = replaceActive(st.messages, event.turnId) { active ->
                        active.copy(
                            type = ChatMessageType.ERROR,
                            text = event.message,
                            status = ChatMessageStatus.FAILED,
                            retryable = event.retryable,
                            confirmation = null,
                            executionTierLabel = tierLabel(event.executionPath),
                            executionPath = event.executionPath
                        )
                    }
                )
            }

            is AgentEvent.TurnCancelled -> _state.update { st ->
                st.copy(
                    activeTurnId = null,
                    isAgentBusy = false,
                    pendingConfirmationId = null,
                    messages = replaceActive(st.messages, event.turnId) { active ->
                        active.copy(
                            type = ChatMessageType.TEXT,
                            text = event.text,
                            status = ChatMessageStatus.CANCELLED,
                            confirmation = null
                        )
                    }
                )
            }

            is AgentEvent.DebugInfo -> _state.update { st ->
                val index = st.messages.indexOfLast { it.turnId == event.turnId }
                if (index < 0) {
                    st
                } else {
                    st.copy(
                        messages = st.messages.toMutableList().apply {
                            this[index] = this[index].let { msg ->
                                val merged = mergeDetails(msg.details, event.debug)
                                // Performance is attached to the final message for the
                                // collapsible panel; expansion stays default-collapsed.
                                // CR-009: 气泡层级标签同步附带匹配到的领域/能力包/工具或工作流。
                                msg.copy(
                                    details = merged,
                                    performance = merged.performance ?: msg.performance,
                                    executionTierLabel = tierLabelWithAssets(msg.executionTierLabel, merged)
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * Merges two debug payloads of the same turn: the first one (initial model
     * turn) is kept, later ones (tool execution on confirm) fill in the missing
     * pieces.
     */
    private fun mergeDetails(existing: TurnDebugInfo?, incoming: TurnDebugInfo): TurnDebugInfo {
        if (existing == null) return incoming
        return existing.copy(
            performance = incoming.performance ?: existing.performance,
            parsed = incoming.parsed ?: existing.parsed,
            tool = incoming.tool ?: existing.tool,
            state = incoming.state ?: existing.state,
            route = incoming.route ?: existing.route,
            errorCode = incoming.errorCode ?: existing.errorCode,
            replayed = incoming.replayed || existing.replayed,
            // CR-009: 合并时保留领域 / 能力包 / 工作流等匹配资产信息。
            cr008 = incoming.cr008 ?: existing.cr008,
            // CR-011: 合并时保留 RAG 执行信息（后到的 payload 可能只补性能/工具）。
            rag = incoming.rag ?: existing.rag
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Replaces the active (PROCESSING / CONFIRMATION) bubble of [turnId] in place,
     * keeping the turn's message count stable for simple operations.
     */
    private fun replaceActive(
        messages: List<ChatMessage>,
        turnId: String,
        transform: (ChatMessage) -> ChatMessage
    ): List<ChatMessage> {
        val index = activeBubbleIndex(messages, turnId)
        if (index < 0) return messages
        return messages.toMutableList().apply { this[index] = transform(this[index]) }
    }

    private fun replaceActive(
        messages: List<ChatMessage>,
        turnId: String,
        message: ChatMessage
    ): List<ChatMessage> {
        val index = activeBubbleIndex(messages, turnId)
        if (index < 0) return messages + message
        return messages.toMutableList().apply { this[index] = message }
    }

    private fun activeBubbleIndex(messages: List<ChatMessage>, turnId: String): Int =
        messages.indexOfLast {
            it.turnId == turnId &&
                it.role == ChatRole.AGENT &&
                (it.type == ChatMessageType.PROCESSING || it.type == ChatMessageType.CONFIRMATION)
        }

    private fun newMessage(
        sessionId: String,
        turnId: String,
        requestId: String,
        role: ChatRole,
        type: ChatMessageType,
        text: String,
        status: ChatMessageStatus,
        confirmation: ConfirmationUiModel? = null,
        retryOfRequestId: String? = null,
        executionTierLabel: String? = null,
        executionPath: AgentExecutionPath? = null,
        inputSource: AgentInputSource? = null
    ) = ChatMessage(
        messageId = UUID.randomUUID().toString(),
        sessionId = sessionId,
        turnId = turnId,
        requestId = requestId,
        role = role,
        type = type,
        text = text,
        timestamp = System.currentTimeMillis(),
        status = status,
        confirmation = confirmation,
        retryOfRequestId = retryOfRequestId,
        executionTierLabel = executionTierLabel,
        executionPath = executionPath,
        inputSource = inputSource
    )

    private fun tierLabel(path: AgentExecutionPath?): String? =
        path?.finalTier?.let { IntentTier.label(it) }

    /**
     * CR-009 + CR-010: 在层级标签基础上追加当前指令匹配到的领域 / 能力包 / 工具或工作流。
     * 例："L1 · 本地模型 · 座舱舒适/空调与温控+座椅与舒适/调节温度"
     * CR-010 展示：能力包与工具/工作流在气泡首行只显示中文名，代码 ID 仅作兜底。
     */
    private fun tierLabelWithAssets(base: String?, debug: TurnDebugInfo): String? {
        val parts = mutableListOf<String>()
        val cr008 = debug.cr008
        if (cr008 != null) {
            cr008.domain?.let {
                parts += TurnDebugFormatter.DomainCodeLabels.label(it)
            }
            if (cr008.selectedPacks.isNotEmpty()) {
                // 中文名优先；若载荷未带中文（旧事件），回退代码 ID。
                val packNames = cr008.selectedPackNames.ifEmpty { cr008.selectedPacks }
                parts += packNames.joinToString("+")
            }
            val workflowName = cr008.workflowName
            val toolName = debug.tool?.toolName ?: debug.parsed?.intents?.firstOrNull()?.toolName
            val asset = workflowName ?: toolName
                ?: cr008.workflowId ?: debug.tool?.toolId ?: debug.parsed?.intents?.firstOrNull()?.toolId
            asset?.let { parts += it }
        }
        // CR-011: 本轮用到了 RAG 就追加标识（区分 工具/知识 路径）。
        debug.rag?.takeIf { it.retrievalExecuted }?.let { rag ->
            val kind = if (rag.retrievalType?.contains("Knowledge") == true) "知识" else "工具"
            parts += "RAG·$kind"
        }
        if (parts.isEmpty()) return base
        val prefix = base?.let { "$it · " } ?: ""
        return prefix + parts.joinToString("/")
    }

    private fun showHint(text: String) {
        _hints.tryEmit(text)
    }
}
