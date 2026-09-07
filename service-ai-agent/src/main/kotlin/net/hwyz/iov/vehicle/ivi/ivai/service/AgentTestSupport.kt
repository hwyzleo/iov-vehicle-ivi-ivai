package net.hwyz.iov.vehicle.ivi.ivai.service

import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot

/**
 * 测试支撑能力（IVI-IVAI-DSN-CR-012）。仅 Debug/Test 构建启用；Release 恒拒绝
 * （门禁 IVAI-TEST-003）。供 [net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway.AgentCommandGateway]
 * 实现方按需调用，与 [AiAgentClient] 分开以保持普通客户端契约不受测试 API 污染。
 */
interface AgentTestSupport {

    /** 批次执行环境门禁：仅 Debug/Test（Mock Adapter + STUB 模式）允许。 */
    fun isTestEnvironmentAllowed(): Boolean

    /** 创建隔离的测试 Session（用例之间无上下文污染，REQ-121）。 */
    fun createTestSession(): String

    /** 取消活动请求（单条超时后继续下一条，IVAI-TEST-005）。 */
    fun cancelRequest(requestId: String): Boolean

    /**
     * 等待服务端释放活动 Turn；超时返回 false（IVAI-TEST-004 串行语义加固）。
     *
     * 终态事件在 [AiAgentClient.submit] 的 process 内部发出，而 `activeTurn` 闸门要等
     * process **完全返回**（finally）才复位——事件到达 ≠ 闸门释放。批次提交下一条前必须
     * 先等闸门真正空闲，否则全局单飞闸门会拒绝后续所有提交。
     */
    suspend fun awaitIdle(timeoutMs: Long): Boolean

    /** 强制取消当前活动请求（不校验 requestId；阻塞调用未被中断时的兜底，IVAI-TEST-009）。 */
    fun cancelActiveRequest(): Boolean

    /** 按 requestId 读取测试快照（脱敏契约；Release 恒为 null）。 */
    fun evaluationSnapshot(requestId: String): AgentEvaluationSnapshot?
}
