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
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.TurnDebugInfo
import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.model.AgentPerformanceMetrics
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentCommand
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentInputSource
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentSessionSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEvent
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
    fun `DebugInfo 事件附加到当前轮次的消息且携带分段性能`() = runTest(dispatcher) {
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
                    performance = AgentPerformanceMetrics(
                        requestId = requestId,
                        modelCallTotalMs = 120,
                        endToEndMs = 140,
                        unattributedMs = 20
                    ),
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
        assertEquals("LOCAL_TOOL", details!!.route)
        assertEquals(120L, details.performance?.modelCallTotalMs)
        // CR-004：System Prompt 不进入聊天区；性能挂到消息但不拼接进 text，默认收起
        assertTrue(result.text.isNotBlank())
        assertFalse(result.text.contains("你是车控助手"))
        assertEquals(140L, result.performance?.endToEndMs)
        assertFalse(result.isPerformanceExpanded, "性能详情默认收起")
    }

    @Test
    fun `CR-009 DebugInfo 附带领域能力包工具且合并时保留`() = runTest(dispatcher) {
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
            AgentEvent.ToolExecutionFinished(
                "sess", "turn-1", requestId, "climate.power_on",
                ExecutionStatus.SUCCEEDED, "已打开空调", retryable = false,
                executionPath = net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentExecutionPath(
                    initialTier = net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier.L0_DETERMINISTIC_TOOL,
                    finalTier = net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier.L0_DETERMINISTIC_TOOL,
                    finalReasonCode = "L0_MATCH"
                )
            )
        )
        runCurrent()

        // DebugInfo 携带 CR-009/CR-010 匹配资产（领域中文 / 能力包中文 / 工具中文 + 代码 ID）。
        gateway.emit(
            AgentEvent.DebugInfo(
                "sess", "turn-1", requestId,
                TurnDebugInfo(
                    turnId = "turn-1",
                    requestId = requestId,
                    state = "SUCCEEDED",
                    route = "LOCAL_TOOL",
                    intentTier = "L0_DETERMINISTIC_TOOL",
                    finalTier = "L0_DETERMINISTIC_TOOL",
                    tool = net.hwyz.iov.vehicle.ivi.ivai.agent.event.ToolDebugInfo(
                        toolId = "climate.power_on", toolName = "打开空调", status = "SUCCEEDED"
                    ),
                    cr008 = net.hwyz.iov.vehicle.ivi.ivai.agent.event.Cr008DebugInfo(
                        domain = "BD01",
                        operationType = "CONTROL",
                        selectedPacks = listOf("cabin.climate"),
                        selectedPackNames = listOf("空调与温控"),
                        workflowId = null
                    )
                )
            )
        )
        runCurrent()

        val result = viewModel.state.value.messages.last()
        val label = result.executionTierLabel
        assertNotNull(label)
        assertTrue(label!!.contains("L0"), "层级标签应保留 L0")
        assertTrue(label.contains("座舱舒适"), "层级标签应附带领域中文名")
        assertTrue(label.contains("空调与温控"), "气泡首行能力包只显示中文名")
        assertFalse(label.contains("cabin.climate"), "气泡首行不应出现能力包代码 ID")
        assertTrue(label.contains("打开空调"), "气泡首行工具只显示中文名")
        assertFalse(label.contains("climate.power_on"), "气泡首行不应出现工具代码 ID")
        // 合并后 cr008 信息保留。
        assertEquals("BD01", result.details?.cr008?.domain)
        assertEquals(listOf("cabin.climate"), result.details?.cr008?.selectedPacks)
    }

    @Test
    fun `RAG 执行时气泡首行追加 RAG 标识（知识路径）`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        viewModel.attach(gateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("胎压报警是什么意思"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()
        val requestId = viewModel.state.value.messages.first().requestId!!

        // 真实顺序：先 Reply（带 L2 执行路径），再 finish→DebugInfo（带 rag + cr008）。
        gateway.emit(
            AgentEvent.UserSubmitted("sess", "turn-rag", requestId, "胎压报警是什么意思"),
            AgentEvent.ProcessingStarted("sess", "turn-rag", requestId),
            AgentEvent.Reply(
                "sess", "turn-rag", requestId, "胎压报警指轮胎气压低于标准…",
                executionPath = net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentExecutionPath(
                    initialTier = net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier.L2_LOCAL_KNOWLEDGE,
                    finalTier = net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier.L2_LOCAL_KNOWLEDGE,
                    finalReasonCode = "L2_KNOWLEDGE_DOMAIN"
                )
            ),
            AgentEvent.DebugInfo(
                "sess", "turn-rag", requestId,
                TurnDebugInfo(
                    turnId = "turn-rag",
                    requestId = requestId,
                    state = "REPLY_READY",
                    route = "LOCAL_DIALOGUE",
                    intentTier = "L2_LOCAL_KNOWLEDGE",
                    finalTier = "L2_LOCAL_KNOWLEDGE",
                    rag = net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagExecutionInfo(
                        configuredEnabled = true,
                        runtimeStatus = net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeStatus.READY,
                        retrievalExecuted = true,
                        retrievalType = "KnowledgeRagRetriever",
                        queryText = "胎压报警是什么意思",
                        modelId = "BAAI/bge-m3",
                        topK = 5,
                        retrievedTitles = listOf("故障/胎压报警")
                    )
                )
            )
        )
        runCurrent()

        val label = viewModel.state.value.messages.last().executionTierLabel
        assertNotNull(label)
        assertTrue(label!!.contains("L2"), "层级标签应保留 L2")
        assertTrue(label.contains("RAG·知识"), "用到了 RAG 应在首行追加 RAG·知识 标识")
    }

    @Test
    fun `合并多条 DebugInfo 时保留 RAG 信息`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        viewModel.attach(gateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.InputChanged("我感觉有点冷"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()
        val requestId = viewModel.state.value.messages.first().requestId!!

        val rag = net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagExecutionInfo(
            configuredEnabled = true,
            runtimeStatus = net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeStatus.READY,
            retrievalExecuted = true,
            retrievalType = "ToolRagRetriever",
            queryText = "我感觉有点冷",
            modelId = "BAAI/bge-m3",
            topK = 5,
            retrievedTitles = listOf("调高温度")
        )

        // 第一条 DebugInfo 带 RAG；后续（工具执行）DebugInfo 不带 RAG——不得覆盖丢失。
        gateway.emit(
            AgentEvent.UserSubmitted("sess", "turn-rag2", requestId, "我感觉有点冷"),
            AgentEvent.ProcessingStarted("sess", "turn-rag2", requestId),
            AgentEvent.DebugInfo(
                "sess", "turn-rag2", requestId,
                TurnDebugInfo(turnId = "turn-rag2", requestId = requestId, rag = rag)
            ),
            AgentEvent.DebugInfo(
                "sess", "turn-rag2", requestId,
                TurnDebugInfo(
                    turnId = "turn-rag2", requestId = requestId,
                    tool = net.hwyz.iov.vehicle.ivi.ivai.agent.event.ToolDebugInfo(
                        toolId = "climate.temperature_increase", toolName = "调高温度", status = "SUCCEEDED"
                    )
                )
            )
        )
        runCurrent()

        val details = viewModel.state.value.messages.last().details
        assertNotNull(details?.rag)
        assertEquals("BAAI/bge-m3", details?.rag?.modelId)
        assertTrue(details?.rag?.retrievalExecuted == true)
    }

    @Test
    fun `性能详情按 messageId 展开与收起`() = runTest(dispatcher) {
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
            AgentEvent.DebugInfo(
                "sess", "turn-1", requestId,
                TurnDebugInfo(
                    turnId = "turn-1",
                    requestId = requestId,
                    performance = AgentPerformanceMetrics(requestId = requestId, endToEndMs = 100)
                )
            )
        )
        runCurrent()

        val message = viewModel.state.value.messages.last()
        assertEquals(ChatMessageType.PROCESSING, message.type)
        assertFalse(message.isPerformanceExpanded)

        viewModel.onAction(ChatUiAction.TogglePerformanceDetails(message.messageId))
        runCurrent()
        assertTrue(viewModel.state.value.messages.last().isPerformanceExpanded)

        // 再次点击收起；不影响其他消息
        viewModel.onAction(ChatUiAction.TogglePerformanceDetails(message.messageId))
        runCurrent()
        assertFalse(viewModel.state.value.messages.last().isPerformanceExpanded)
    }

    @Test
    fun `流式增量逐字更新处理中气泡，最终结果替换`() = runTest(dispatcher) {
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
            AgentEvent.ProcessingStarted("sess", "turn-1", requestId)
        )
        runCurrent()

        gateway.emit(AgentEvent.StreamingDelta("sess", "turn-1", requestId, "{\"route\":"))
        runCurrent()
        assertEquals("{\"route\":", viewModel.state.value.messages.last().text)
        assertEquals(ChatMessageType.PROCESSING, viewModel.state.value.messages.last().type)

        gateway.emit(AgentEvent.StreamingDelta("sess", "turn-1", requestId, "{\"route\":\"LOCAL_TOOL\",\"intents\":[]}"))
        gateway.emit(AgentEvent.ToolExecutionFinished("sess", "turn-1", requestId, "climate.power_on", ExecutionStatus.SUCCEEDED, "已打开空调", retryable = false))
        runCurrent()

        val last = viewModel.state.value.messages.last()
        assertEquals(ChatMessageType.TOOL_RESULT, last.type)
        assertEquals("已打开空调", last.text)
        assertFalse(last.text.contains("LOCAL_TOOL"), "最终气泡不残留流式 JSON")
    }

    @Test
    fun `语音最终结果以 VOICE_ASR 提交并追加用户消息`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        val voiceEngine = StubSpeechEngine()
        val voiceGateway = FakeVoiceGateway(voiceEngine)
        viewModel.attach(gateway, voiceGateway)
        runCurrent()

        viewModel.onAction(ChatUiAction.VoiceButtonDown)
        runCurrent()
        assertEquals(1, voiceGateway.createCalls)

        voiceEngine.emit(SpeechRecognitionEvent.Listening)
        runCurrent()
        assertEquals(VoiceInputState.Listening(""), viewModel.voiceState.value)
        viewModel.onAction(ChatUiAction.VoiceButtonUp)
        runCurrent()
        assertEquals(VoiceInputState.Finalizing, viewModel.voiceState.value)
        voiceEngine.emit(SpeechRecognitionEvent.FinalResult("打开空调"))
        runCurrent()

        assertEquals(1, gateway.submitted.size)
        assertEquals("打开空调", gateway.submitted.first().first)
        assertEquals(AgentInputSource.VOICE_ASR, gateway.submitted.first().third)

        val userMsg = viewModel.state.value.messages.filter { it.role == ChatRole.USER }.last()
        assertEquals("打开空调", userMsg.text)
        assertEquals(AgentInputSource.VOICE_ASR, userMsg.inputSource)
    }

    @Test
    fun `Agent 忙碌时语音入口不启动新会话`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        val voiceGateway = FakeVoiceGateway(StubSpeechEngine())
        viewModel.attach(gateway, voiceGateway)
        runCurrent()

        // Occupy the single-turn slot with a text send (no agent event yet).
        viewModel.onAction(ChatUiAction.InputChanged("打开空调"))
        viewModel.onAction(ChatUiAction.SendClicked)
        runCurrent()
        assertTrue(viewModel.state.value.isAgentBusy)

        viewModel.onAction(ChatUiAction.VoiceButtonDown)
        runCurrent()

        assertEquals(0, voiceGateway.createCalls, "忙碌时不得创建语音会话")
    }

    @Test
    fun `语音链路到工具结果完整走通且保留 VOICE_ASR 来源`() = runTest(dispatcher) {
        val viewModel = ChatViewModel()
        val gateway = FakeGateway()
        val voiceEngine = StubSpeechEngine()
        val voiceGateway = FakeVoiceGateway(voiceEngine)
        viewModel.attach(gateway, voiceGateway)
        runCurrent()

        // 按住说“打开空调”→ FinalResult → VOICE_ASR 提交
        viewModel.onAction(ChatUiAction.VoiceButtonDown)
        runCurrent()
        voiceEngine.emit(SpeechRecognitionEvent.Listening)
        runCurrent()
        viewModel.onAction(ChatUiAction.VoiceButtonUp)
        runCurrent()
        voiceEngine.emit(SpeechRecognitionEvent.FinalResult("打开空调"))
        runCurrent()

        val voiceTurn = gateway.submitted.single()
        assertEquals("打开空调", voiceTurn.first)
        assertEquals(AgentInputSource.VOICE_ASR, voiceTurn.third)
        val requestId = viewModel.state.value.messages.filter { it.role == ChatRole.USER }.last().requestId!!

        // Agent 链路返回事件 → 工具结果，语音来源与文本走同一 Schema/Policy/Tool 链路
        gateway.emit(
            AgentEvent.UserSubmitted("sess", "turn-v", requestId, "打开空调"),
            AgentEvent.ProcessingStarted("sess", "turn-v", requestId),
            AgentEvent.ToolExecutionStarted("sess", "turn-v", requestId, "climate.power_on", "正在执行…"),
            AgentEvent.ToolExecutionFinished("sess", "turn-v", requestId, "climate.power_on", ExecutionStatus.SUCCEEDED, "已打开空调", retryable = false)
        )
        runCurrent()

        val messages = viewModel.state.value.messages
        val user = messages.filter { it.role == ChatRole.USER }.last()
        assertEquals(ChatMessageStatus.FINAL, user.status)
        assertEquals(AgentInputSource.VOICE_ASR, user.inputSource)
        assertEquals(ChatMessageType.TOOL_RESULT, messages.last().type)
        assertEquals("已打开空调", messages.last().text)
    }

    private class FakeVoiceGateway(
        private val engine: StubSpeechEngine
    ) : VoiceInputGateway {
        var createCalls = 0
        var capabilityValue: SpeechCapability = SpeechCapability.SYSTEM_SERVICE

        override suspend fun createVoiceSession(): VoiceEngineSession? {
            createCalls++
            return VoiceEngineSession(
                engine = engine,
                languageTag = "zh-CN",
                preferOffline = true,
                recognitionTimeoutMs = 30_000L
            )
        }

        override fun capability(): SpeechCapability = capabilityValue
    }

    private class FakeGateway : ChatAgentGateway {
        val eventFlow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 100)
        override fun observeEvents(sessionId: String): Flow<AgentEvent> = eventFlow

        val submitted = mutableListOf<Triple<String, String, AgentInputSource>>()
        val confirms = mutableListOf<String>()
        val cancels = mutableListOf<String>()

        override suspend fun submit(command: AgentCommand): Boolean = when (command) {
            is AgentCommand.HandleText -> {
                submitted += Triple(command.text, command.turnId, command.inputSource)
                true
            }
            is AgentCommand.Confirm -> {
                confirms += command.confirmationId
                true
            }
            is AgentCommand.Cancel -> {
                cancels += command.confirmationId
                true
            }
        }

        override suspend fun getSessionSnapshot(sessionId: String): AgentSessionSnapshot =
            AgentSessionSnapshot(
                sessionId = sessionId,
                history = emptyList(),
                pendingConfirmationId = null,
                activeTurnId = null
            )

        override fun sessionId(): String = "sess"

        override fun promptSnapshot(): PromptSnapshot? = null

        fun emit(vararg events: AgentEvent) {
            events.forEach { eventFlow.tryEmit(it) }
        }
    }
}
