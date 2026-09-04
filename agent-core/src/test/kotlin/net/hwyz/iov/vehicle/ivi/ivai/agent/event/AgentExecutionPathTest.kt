package net.hwyz.iov.vehicle.ivi.ivai.agent.event

import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSource
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-005 气泡层级标识数据契约：AgentExecutionPath 可序列化（事件/快照恢复），
 * 层级标签映射稳定（L0·本地直达 … 未执行·已拒绝）。
 */
class AgentExecutionPathTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `执行路径可序列化往返`() {
        val path = AgentExecutionPath(
            initialTier = IntentTier.L0_DETERMINISTIC_TOOL,
            finalTier = IntentTier.L1_LOCAL_TOOL_REASONING,
            transitions = listOf(
                TierTransition(IntentTier.L0_DETERMINISTIC_TOOL, IntentTier.L1_LOCAL_TOOL_REASONING, "L0_RULE_AMBIGUOUS")
            ),
            finalReasonCode = "L0_RULE_AMBIGUOUS",
            candidateSource = CandidateSource.L1_LOCAL_LLM
        )
        val encoded = json.encodeToString(AgentExecutionPath.serializer(), path)
        val decoded = json.decodeFromString(AgentExecutionPath.serializer(), encoded)
        assertEquals(path, decoded)
    }

    @Test
    fun `L1 转 L3 降级路径往返`() {
        val path = AgentExecutionPath(
            initialTier = IntentTier.L2_LOCAL_KNOWLEDGE,
            finalTier = IntentTier.L3_CLOUD_AI,
            transitions = listOf(
                TierTransition(IntentTier.L2_LOCAL_KNOWLEDGE, IntentTier.L3_CLOUD_AI, "L2_KNOWLEDGE_UNAVAILABLE")
            ),
            finalReasonCode = "L2_KNOWLEDGE_UNAVAILABLE",
            candidateSource = null
        )
        val decoded = json.decodeFromString(
            AgentExecutionPath.serializer(),
            json.encodeToString(AgentExecutionPath.serializer(), path)
        )
        assertEquals(IntentTier.L3_CLOUD_AI, decoded.finalTier)
        assertTrue(decoded.transitions.single().from == IntentTier.L2_LOCAL_KNOWLEDGE)
    }

    @Test
    fun `层级标签映射符合设计表格`() {
        assertEquals("L0 · 本地直达", IntentTier.label(IntentTier.L0_DETERMINISTIC_TOOL))
        assertEquals("L1 · 本地模型", IntentTier.label(IntentTier.L1_LOCAL_TOOL_REASONING))
        assertEquals("L2 · 本地知识", IntentTier.label(IntentTier.L2_LOCAL_KNOWLEDGE))
        assertEquals("L3 · 云端 AI", IntentTier.label(IntentTier.L3_CLOUD_AI))
        assertEquals("未执行 · 已拒绝", IntentTier.label(IntentTier.REJECT))
    }
}
