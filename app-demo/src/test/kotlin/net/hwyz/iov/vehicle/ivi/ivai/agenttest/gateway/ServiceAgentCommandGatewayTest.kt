package net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentCommand
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentInputSource
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentSessionSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentTestSupport
import net.hwyz.iov.vehicle.ivi.ivai.service.AiAgentClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-012 网关单测：Test 侧与 ChatViewModel 使用**同一** AgentCommand.HandleText 入口
 * （IVAI-REQ-115 集成验收 #2），并正确透传 requestId/sessionId/source。
 */
class ServiceAgentCommandGatewayTest {

    private class RecordingClient : AiAgentClient {
        val commands = mutableListOf<AgentCommand>()
        var lastSubmit: AgentCommand? = null
        override suspend fun submit(command: AgentCommand): Boolean {
            commands += command
            lastSubmit = command
            return true
        }
        override fun observeEvents(sessionId: String): Flow<AgentEvent> = emptyFlow()
        override suspend fun getSessionSnapshot(sessionId: String): AgentSessionSnapshot =
            throw UnsupportedOperationException()
    }

    private class FakeTestSupport : AgentTestSupport {
        var allowed = true
        val sessions = mutableListOf<String>()
        var cancelled = mutableListOf<String>()
        var snapshot: AgentEvaluationSnapshot? = null
        override fun isTestEnvironmentAllowed(): Boolean = allowed
        override fun createTestSession(): String {
            sessions += "test-session-${sessions.size}"
            return sessions.last()
        }
        override fun cancelRequest(requestId: String): Boolean {
            cancelled += requestId
            return true
        }
        override suspend fun awaitIdle(timeoutMs: Long): Boolean = true
        override fun cancelActiveRequest(): Boolean {
            cancelActiveCalls++
            return true
        }
        var cancelActiveCalls = 0
        override fun evaluationSnapshot(requestId: String): AgentEvaluationSnapshot? = snapshot
    }

    @Test
    fun `submitText 与 ChatViewModel 使用同一 HandleText 命令形状`() = runBlocking {
        val client = RecordingClient()
        val support = FakeTestSupport()
        val gateway = ServiceAgentCommandGateway(client, support)

        val accepted = gateway.submitText(
            sessionId = "sess-1",
            requestId = "req-1",
            text = "打开空调",
            source = AgentInputSource.TEXT_CHAT
        )

        assertTrue(accepted)
        val command = client.lastSubmit
        assertTrue(command is AgentCommand.HandleText)
        command as AgentCommand.HandleText
        assertEquals("sess-1", command.sessionId)
        assertEquals("req-1", command.requestId)
        assertEquals("打开空调", command.text)
        assertEquals(AgentInputSource.TEXT_CHAT, command.inputSource)
        // turnId 与 requestId 一一关联（设计：requestId、testRunId 与 caseId 建立关联）。
        assertEquals("req-1", command.turnId)
    }

    @Test
    fun `测试支撑透传 - Session 创建 快照 取消`() = runBlocking {
        val client = RecordingClient()
        val support = FakeTestSupport()
        val gateway = ServiceAgentCommandGateway(client, support)

        val sessionId = gateway.createTestSession()
        assertEquals("test-session-0", sessionId)
        assertSame(support.snapshot, gateway.evaluationSnapshot("req-x"))
        gateway.cancelRequest("req-x")
        assertEquals(listOf("req-x"), support.cancelled)
    }

    @Test
    fun `串行语义透传 - awaitIdle 与强制取消委托到测试支撑`() = runBlocking {
        val client = RecordingClient()
        val support = FakeTestSupport()
        val gateway = ServiceAgentCommandGateway(client, support)

        assertTrue(gateway.awaitIdle(1_000))
        assertTrue(gateway.cancelActiveRequest())
        assertEquals(1, support.cancelActiveCalls)
    }
}
