package net.hwyz.iov.vehicle.ivi.ivai.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.ComposedMessage
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.TurnDebugInfo
import net.hwyz.iov.vehicle.ivi.ivai.service.SessionSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ViewModel unit tests (IVI-IVAI-DSN-CR-002): empty input, single request on
 * repeated clicks, event→message mapping, one-shot confirm/cancel, follow-up
 * slot filling, and error/retry request association.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `空输入不发送`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        viewModel.attach(gateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("   "))
        assertFalse(viewModel.submitFromInput(), "空白输入应返回 false")
        runCurrent()

        assertTrue(gateway.submitted.isEmpty())
        assertTrue(viewModel.state.value.messages.isEmpty())
    }

    @Test
    fun `有效输入提交返回 true 追加用户消息并清空 inputText`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        viewModel.attach(gateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("打开空调"))
        val accepted = viewModel.submitFromInput()
        runCurrent()

        assertTrue(accepted, "有效输入应返回 true")
        assertEquals(1, gateway.submitted.size)
        val state = viewModel.state.value
        assertEquals(1, state.messages.count { it.role == ChatRole.USER })
        assertEquals("打开空调", state.messages.first().text)
        assertEquals("", state.inputText, "发送后 inputText 应清空")
        assertTrue(state.isAgentBusy)
    }

    @Test
    fun `处理中重复点击发送只提交一次请求`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        viewModel.attach(gateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("打开空调"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()
        // No agent event yet → still busy; a second click must not submit.
        viewModel.onAction(ChatUiAction.InputChanged("打开空调"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()

        assertEquals(1, gateway.submitted.size)
        assertTrue(viewModel.state.value.isAgentBusy)
    }

    @Test
    fun `Agent 事件正确映射为消息：用户气泡到工具结果`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        viewModel.attach(gateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("打开空调"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()
        val requestId = viewModel.state.value.messages.first().requestId!!

        gateway.emit(
            AgentEvent.UserSubmitted("sess", "turn-1", requestId, "打开空调"),
            AgentEvent.ProcessingStarted("sess", "turn-1", requestId),
            AgentEvent.ToolExecutionStarted("sess", "turn-1", requestId, "climate.power_on", "正在执行「空调」…"),
            AgentEvent.ToolExecutionFinished("sess", "turn-1", requestId, "climate.power_on", ExecutionStatus.SUCCEEDED, "已打开空调", retryable = false)
        )
        runCurrent()

        val state = viewModel.state.value
        val messages = state.messages
        assertEquals(2, messages.size, "简单工具回合应只有用户气泡 + 结果气泡（原位更新）")
        assertEquals(ChatRole.USER, messages[0].role)
        assertEquals(ChatMessageStatus.FINAL, messages[0].status)
        assertEquals(ChatMessageType.TOOL_RESULT, messages[1].type)
        assertEquals(ChatMessageStatus.FINAL, messages[1].status)
        assertEquals("已打开空调", messages[1].text)
        assertFalse(state.isAgentBusy)
        assertNull(state.activeTurnId)
    }

    @Test
    fun `追问映射为左侧 TEXT 且可补槽恢复任务`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        viewModel.attach(gateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("温度调到"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()
        val requestId = viewModel.state.value.messages.first().requestId!!

        gateway.emit(
            AgentEvent.UserSubmitted("sess", "turn-1", requestId, "温度调到"),
            AgentEvent.ProcessingStarted("sess", "turn-1", requestId),
            AgentEvent.Reply("sess", "turn-1", requestId, "请问需要补充：temperature。")
        )
        runCurrent()

        var state = viewModel.state.value
        assertEquals(2, state.messages.size)
        assertEquals(ChatMessageType.TEXT, state.messages[1].type)
        assertTrue(state.messages[1].text.contains("temperature"))
        assertFalse(state.isAgentBusy)

        // User fills the slot: a new turn is submitted as a follow-up.
        viewModel.onAction(ChatUiAction.InputChanged("24度"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()

        state = viewModel.state.value
        assertEquals(3, state.messages.size)
        assertEquals("24度", gateway.submitted.last().first)
        assertEquals(ChatRole.USER, state.messages.last().role)
    }

    @Test
    fun `确认只处理一次且按钮立即失效`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        viewModel.attach(gateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("打开空调"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()
        val requestId = viewModel.state.value.messages.first().requestId!!
        val confirmationId = "conf-1"

        gateway.emit(
            AgentEvent.UserSubmitted("sess", "turn-1", requestId, "打开空调"),
            AgentEvent.ProcessingStarted("sess", "turn-1", requestId),
            AgentEvent.ConfirmationRequired("sess", "turn-1", requestId, confirmationId, "climate.power_on", "空调", "确认执行「空调」？")
        )
        runCurrent()

        var state = viewModel.state.value
        assertEquals(ChatMessageType.CONFIRMATION, state.messages.last().type)
        assertEquals(ChatMessageStatus.WAITING_USER, state.messages.last().status)
        assertEquals(confirmationId, state.pendingConfirmationId)

        viewModel.onAction(ChatUiAction.ConfirmClicked(confirmationId))
        runCurrent()
        state = viewModel.state.value
        assertEquals(listOf(confirmationId), gateway.confirms)
        assertNull(state.pendingConfirmationId, "点击确认后 pendingConfirmationId 立即清空")
        assertTrue(state.isAgentBusy)

        // Second click (already handled / buttons disabled) must not confirm again.
        viewModel.onAction(ChatUiAction.ConfirmClicked(confirmationId))
        runCurrent()
        assertEquals(1, gateway.confirms.size)
    }

    @Test
    fun `取消只处理一次`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        viewModel.attach(gateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("打开空调"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()
        val requestId = viewModel.state.value.messages.first().requestId!!
        gateway.emit(
            AgentEvent.UserSubmitted("sess", "turn-1", requestId, "打开空调"),
            AgentEvent.ProcessingStarted("sess", "turn-1", requestId),
            AgentEvent.ConfirmationRequired("sess", "turn-1", requestId, "conf-2", "climate.power_on", "空调", "确认执行？")
        )
        runCurrent()

        viewModel.onAction(ChatUiAction.CancelClicked("conf-2"))
        runCurrent()
        assertEquals(listOf("conf-2"), gateway.cancels)

        viewModel.onAction(ChatUiAction.CancelClicked("conf-2"))
        runCurrent()
        assertEquals(1, gateway.cancels.size)
    }

    @Test
    fun `错误显示 ERROR 且重试保留原始请求关联`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        viewModel.attach(gateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("打开空调"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()
        val originalRequestId = viewModel.state.value.messages.first().requestId!!

        gateway.emit(
            AgentEvent.UserSubmitted("sess", "turn-1", originalRequestId, "打开空调"),
            AgentEvent.ProcessingStarted("sess", "turn-1", originalRequestId),
            AgentEvent.ToolExecutionStarted("sess", "turn-1", originalRequestId, "climate.power_on", "正在执行…"),
            AgentEvent.ToolExecutionFinished("sess", "turn-1", originalRequestId, "climate.power_on", ExecutionStatus.FAILED, "模拟执行失败：网络超时", errorCode = "IVAI-EXEC-001", retryable = true)
        )
        runCurrent()

        var state = viewModel.state.value
        val failed = state.messages.last()
        assertEquals(ChatMessageType.TOOL_RESULT, failed.type)
        assertEquals(ChatMessageStatus.FAILED, failed.status)
        assertTrue(failed.retryable)

        viewModel.onAction(ChatUiAction.RetryClicked(failed.messageId))
        runCurrent()
        state = viewModel.state.value

        val userMessages = state.messages.filter { it.role == ChatRole.USER }
        assertEquals(2, userMessages.size)
        val retry = userMessages.last()
        assertEquals(originalRequestId, retry.retryOfRequestId, "重试保留原始请求关联")
        assertNotEquals(originalRequestId, retry.requestId, "重试使用新 requestId 避免命中旧失败缓存")
        assertEquals("打开空调", gateway.submitted.last().first)
    }

    @Test
    fun `TurnFailed 显示 ERROR 且允许重试`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        viewModel.attach(gateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("打开空调"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()
        val requestId = viewModel.state.value.messages.first().requestId!!

        gateway.emit(
            AgentEvent.UserSubmitted("sess", "turn-1", requestId, "打开空调"),
            AgentEvent.ProcessingStarted("sess", "turn-1", requestId),
            AgentEvent.TurnFailed("sess", "turn-1", requestId, "模型服务暂不可用，请稍后重试", errorCode = "IVAI-MODEL-001", retryable = true)
        )
        runCurrent()

        val state = viewModel.state.value
        val error = state.messages.last()
        assertEquals(ChatMessageType.ERROR, error.type)
        assertEquals(ChatMessageStatus.FAILED, error.status)
        assertTrue(error.retryable)
        assertFalse(state.isAgentBusy)
    }

    @Test
    fun `等待确认时普通文本不得发送`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        viewModel.attach(gateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("打开空调"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()
        val requestId = viewModel.state.value.messages.first().requestId!!
        gateway.emit(
            AgentEvent.UserSubmitted("sess", "turn-1", requestId, "打开空调"),
            AgentEvent.ProcessingStarted("sess", "turn-1", requestId),
            AgentEvent.ConfirmationRequired("sess", "turn-1", requestId, "conf-3", "climate.power_on", "空调", "确认执行？")
        )
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("直接执行吧"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()

        // Only the original send was submitted; the text must not bypass confirmation.
        assertEquals(1, gateway.submitted.size)
        assertEquals("conf-3", viewModel.state.value.pendingConfirmationId)
    }

    @Test
    fun `DebugInfo 事件附加到当前轮次的消息且保留编排与统计`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        viewModel.attach(gateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("打开空调"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()
        val requestId = viewModel.state.value.messages.first().requestId!!

        gateway.emit(
            AgentEvent.UserSubmitted("sess", "turn-1", requestId, "打开空调"),
            AgentEvent.ProcessingStarted("sess", "turn-1", requestId),
            AgentEvent.ToolExecutionStarted("sess", "turn-1", requestId, "climate.power_on", "正在执行…"),
            AgentEvent.ToolExecutionFinished("sess", "turn-1", requestId, "climate.power_on", ExecutionStatus.SUCCEEDED, "已打开空调", retryable = false),
            AgentEvent.DebugInfo(
                "sess", "turn-1", requestId,
                TurnDebugInfo(
                    turnId = "turn-1",
                    requestId = requestId,
                    composedMessages = listOf(
                        ComposedMessage("system", "你是车控助手…"),
                        ComposedMessage("user", "打开空调")
                    ),
                    rawModelContent = """{"route":"LOCAL_TOOL"}""",
                    modelLatencyMs = 120,
                    totalLatencyMs = 140,
                    state = "SUCCEEDED",
                    route = "LOCAL_TOOL"
                )
            )
        )
        runCurrent()

        val result = viewModel.state.value.messages.last()
        assertEquals(ChatMessageType.TOOL_RESULT, result.type)
        val details = result.details
        assertNotNull(details)
        assertEquals(2, details!!.composedMessages.size)
        assertEquals("system", details.composedMessages.first().role)
        assertEquals(120, details.modelLatencyMs)
        assertEquals(140, details.totalLatencyMs)
        assertEquals("LOCAL_TOOL", details.route)
    }

    private class FakeGateway : ChatAgentGateway {
        val eventFlow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 100)
        override val events: Flow<AgentEvent> get() = eventFlow

        val submitted = mutableListOf<Pair<String, String>>()
        val confirms = mutableListOf<String>()
        val cancels = mutableListOf<String>()

        override fun submit(text: String, turnId: String, requestId: String): String? {
            submitted += text to turnId
            return requestId
        }

        override fun confirm(confirmationId: String): Boolean {
            confirms += confirmationId
            return true
        }

        override fun cancel(confirmationId: String): Boolean {
            cancels += confirmationId
            return true
        }

        override fun sessionSnapshot(): SessionSnapshot =
            SessionSnapshot(sessionId = "sess", history = emptyList(), pendingConfirmationId = null)

        override fun sessionId(): String = "sess"

        fun emit(vararg events: AgentEvent) {
            events.forEach { eventFlow.tryEmit(it) }
        }
    }
}
