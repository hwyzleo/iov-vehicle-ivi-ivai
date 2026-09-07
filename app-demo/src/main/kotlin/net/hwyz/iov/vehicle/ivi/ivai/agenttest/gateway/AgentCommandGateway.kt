package net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway

import kotlinx.coroutines.flow.Flow
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentInputSource

/**
 * 统一 AgentCommand 提交门面（IVI-IVAI-DSN-CR-012）。
 *
 * Chat 与 Test Runner 最终都通过 [net.hwyz.iov.vehicle.ivi.ivai.service.AiAgentClient]
 * 的 [net.hwyz.iov.vehicle.ivi.ivai.service.AgentCommand.HandleText] 入口进入同一
 * Agent 服务（不得建立第二套路由 / 检索 / 执行实现）。本接口只统一：
 *  - requestId / sessionId 的显式传递；
 *  - 事件订阅；
 *  - 测试快照的按 requestId 读取（脱敏契约，仅 debug/test）。
 *
 * 它不是新的业务层；测试页不得直接依赖 DomainRouter、Retriever、ModelProvider、
 * ToolExecutor 或 Adapter。
 */
interface AgentCommandGateway {

    /**
     * 以 [AgentInputSource.TEXT_CHAT] 提交一条文本。返回 false 表示服务拒绝
     * （无会话 / 忙碌 / 已终止，对应 IVAI-TEST-004）。
     */
    suspend fun submitText(
        sessionId: String,
        requestId: String,
        text: String,
        source: AgentInputSource = AgentInputSource.TEXT_CHAT
    ): Boolean

    /** 订阅 [sessionId] 的稳定 AgentEvent 流。 */
    fun observe(sessionId: String): Flow<AgentEvent>

    /** 按 requestId 读取结构化测试快照；不可得返回 null（IVAI-TEST-006）。 */
    suspend fun evaluationSnapshot(requestId: String): AgentEvaluationSnapshot?

    /** 创建隔离的测试 Session（用例之间无上下文污染，REQ-121）。 */
    suspend fun createTestSession(): String

    /** 取消活动请求（单条超时后继续下一条，IVAI-TEST-005）。 */
    suspend fun cancelRequest(requestId: String): Boolean

    /**
     * 等待服务端释放上一个活动 Turn；超时返回 false（IVAI-TEST-004 串行语义加固）。
     *
     * 批次提交下一条前必须先等闸门真正空闲：终态事件在 process 内部发出，而
     * `activeTurn` 要等 process 完全返回才释放，事件到达 ≠ 闸门释放。
     */
    suspend fun awaitIdle(timeoutMs: Long): Boolean

    /** 强制取消当前活动请求（不校验 requestId；阻塞调用未被中断时的兜底，IVAI-TEST-009）。 */
    fun cancelActiveRequest(): Boolean
}
