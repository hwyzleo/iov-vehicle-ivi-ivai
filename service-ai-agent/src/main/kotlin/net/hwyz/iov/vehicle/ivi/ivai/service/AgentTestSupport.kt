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

    /** 按 requestId 读取测试快照（脱敏契约；Release 恒为 null）。 */
    fun evaluationSnapshot(requestId: String): AgentEvaluationSnapshot?
}
