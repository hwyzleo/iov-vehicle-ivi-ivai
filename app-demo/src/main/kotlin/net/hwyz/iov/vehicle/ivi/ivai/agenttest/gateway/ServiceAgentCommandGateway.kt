package net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway

import kotlinx.coroutines.flow.Flow
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentCommand
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentInputSource
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentTestSupport
import net.hwyz.iov.vehicle.ivi.ivai.service.AiAgentClient

/**
 * 基于 [AiAgentClient] + [AgentTestSupport] 的真实 [AgentCommandGateway] 实现
 * （IVI-IVAI-DSN-CR-012）。Chat（ChatViewModel→ChatAgentGateway）与测试（本类）
 * 最终进入同一 `AiAgentClient.submit(AgentCommand.HandleText)` 实现，不建立第二套
 * 路由 / 检索 / 执行实现。
 */
class ServiceAgentCommandGateway(
    private val client: AiAgentClient,
    private val testSupport: AgentTestSupport
) : AgentCommandGateway {

    override suspend fun submitText(
        sessionId: String,
        requestId: String,
        text: String,
        source: AgentInputSource
    ): Boolean = client.submit(
        AgentCommand.HandleText(
            sessionId = sessionId,
            requestId = requestId,
            inputSource = AgentInputSource.TEXT_CHAT,
            text = text,
            turnId = requestId
        )
    )

    override fun observe(sessionId: String): Flow<AgentEvent> = client.observeEvents(sessionId)

    override suspend fun evaluationSnapshot(requestId: String): AgentEvaluationSnapshot? =
        testSupport.evaluationSnapshot(requestId)

    override suspend fun createTestSession(): String = testSupport.createTestSession()

    override suspend fun cancelRequest(requestId: String): Boolean = testSupport.cancelRequest(requestId)

    override suspend fun awaitIdle(timeoutMs: Long): Boolean = testSupport.awaitIdle(timeoutMs)

    override fun cancelActiveRequest(): Boolean = testSupport.cancelActiveRequest()
}
