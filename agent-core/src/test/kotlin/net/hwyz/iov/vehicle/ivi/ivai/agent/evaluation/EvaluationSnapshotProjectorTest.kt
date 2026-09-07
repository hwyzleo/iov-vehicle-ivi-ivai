package net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.domain.DomainCandidate
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentExecutionPath
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.TierTransition
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentRoute
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSource
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-012 快照投影器单测：字段来源映射与终态映射（IVAI-REQ-116 / 118）。
 */
class EvaluationSnapshotProjectorTest {

    private val path = AgentExecutionPath(
        initialTier = IntentTier.L0_DETERMINISTIC_TOOL,
        finalTier = IntentTier.L0_DETERMINISTIC_TOOL,
        transitions = emptyList(),
        finalReasonCode = "L0_UNIQUE_MATCH",
        candidateSource = CandidateSource.L0_RULE
    )

    private val domainCandidate = DomainCandidate(BusinessDomainId.CABIN_COMFORT, 1.0, listOf("空调"))

    private val facts = SnapshotFacts(
        requestId = "req-1",
        sessionId = "sess-1",
        executionPath = path,
        initialDomains = listOf(domainCandidate),
        finalDomain = BusinessDomainId.CABIN_COMFORT,
        selectedCapabilityPackIds = setOf("cabin.climate"),
        selectedTarget = ActualTarget(TargetType.TOOL, "climate.power.set"),
        normalizedArguments = buildJsonObject { put("enabled", true) },
        state = AgentState.SUCCEEDED,
        route = AgentRoute.LOCAL_TOOL,
        executionStatus = ExecutionStatus.SUCCEEDED,
        reasonCode = "L0_UNIQUE_MATCH",
        governanceVersion = "ivai-governance-v1-draft"
    )

    @Test
    fun `字段来源映射 - 层级与领域与能力包与目标与参数`() {
        val snapshot = EvaluationSnapshotProjector.project(facts)

        assertEquals("req-1", snapshot.requestId)
        assertEquals("sess-1", snapshot.sessionId)
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, snapshot.initialTier)
        assertEquals(IntentTier.L0_DETERMINISTIC_TOOL, snapshot.finalTier)
        assertEquals(BusinessDomainId.CABIN_COMFORT, snapshot.finalDomain)
        assertEquals(listOf(domainCandidate), snapshot.initialDomains)
        assertEquals(setOf("cabin.climate"), snapshot.selectedCapabilityPackIds)
        assertEquals(ActualTarget(TargetType.TOOL, "climate.power.set"), snapshot.selectedTarget)
        assertEquals(JsonObject(mapOf("enabled" to kotlinx.serialization.json.JsonPrimitive(true))), snapshot.normalizedArguments)
        assertEquals(EvaluationTerminalStatus.SUCCEEDED, snapshot.terminalStatus)
        assertEquals("L0_UNIQUE_MATCH", snapshot.reasonCode)
        assertEquals("ivai-governance-v1-draft", snapshot.governanceVersion)
    }

    @Test
    fun `reasonCode 缺省时回退到执行路径 finalReasonCode`() {
        val snapshot = EvaluationSnapshotProjector.project(facts.copy(reasonCode = null))
        assertEquals("L0_UNIQUE_MATCH", snapshot.reasonCode)
    }

    @Test
    fun `终态映射 - SUCCEEDED`() {
        assertEquals(EvaluationTerminalStatus.SUCCEEDED, EvaluationSnapshotProjector.terminalStatus(
            facts.copy(state = AgentState.SUCCEEDED)
        ))
        assertEquals(EvaluationTerminalStatus.SUCCEEDED, EvaluationSnapshotProjector.terminalStatus(
            facts.copy(state = null, executionStatus = ExecutionStatus.SUCCEEDED)
        ))
    }

    @Test
    fun `终态映射 - REPLIED（纯回复或云端预留）`() {
        assertEquals(
            EvaluationTerminalStatus.REPLIED,
            EvaluationSnapshotProjector.terminalStatus(
                facts.copy(state = null, executionStatus = null, route = AgentRoute.LOCAL_DIALOGUE)
            )
        )
        assertEquals(
            EvaluationTerminalStatus.REPLIED,
            EvaluationSnapshotProjector.terminalStatus(
                facts.copy(state = null, executionStatus = null, route = AgentRoute.CLOUD_AI)
            )
        )
    }

    @Test
    fun `终态映射 - REJECTED`() {
        assertEquals(EvaluationTerminalStatus.REJECTED, EvaluationSnapshotProjector.terminalStatus(
            facts.copy(state = AgentState.REJECTED, executionStatus = null)
        ))
    }

    @Test
    fun `终态映射 - NEED_DIALOGUE（无待确认）`() {
        assertEquals(EvaluationTerminalStatus.NEED_DIALOGUE, EvaluationSnapshotProjector.terminalStatus(
            facts.copy(state = AgentState.WAITING_USER, executionStatus = null, pendingConfirmation = false)
        ))
        assertEquals(EvaluationTerminalStatus.NEED_DIALOGUE, EvaluationSnapshotProjector.terminalStatus(
            facts.copy(state = AgentState.NEED_DIALOGUE, executionStatus = null)
        ))
    }

    @Test
    fun `终态映射 - WAITING_CONFIRMATION`() {
        assertEquals(EvaluationTerminalStatus.WAITING_CONFIRMATION, EvaluationSnapshotProjector.terminalStatus(
            facts.copy(state = AgentState.WAITING_USER, executionStatus = null, pendingConfirmation = true)
        ))
    }

    @Test
    fun `终态映射 - FAILED 与 TIMEOUT 与 CANCELLED`() {
        assertEquals(EvaluationTerminalStatus.FAILED, EvaluationSnapshotProjector.terminalStatus(
            facts.copy(state = AgentState.FAILED, executionStatus = null)
        ))
        assertEquals(EvaluationTerminalStatus.TIMEOUT, EvaluationSnapshotProjector.terminalStatus(
            facts.copy(state = null, executionStatus = ExecutionStatus.TIMEOUT)
        ))
        assertEquals(EvaluationTerminalStatus.CANCELLED, EvaluationSnapshotProjector.terminalStatus(
            facts.copy(cancelled = true)
        ))
    }

    @Test
    fun `快照不携带 Prompt 或密钥等敏感字段`() {
        val snapshot = EvaluationSnapshotProjector.project(facts)
        // 快照契约本身不含 prompt / apiKey / authorization / chainOfThought 字段。
        assertTrue(snapshot.normalizedArguments?.keys?.none {
            it.contains("prompt", ignoreCase = true) ||
                it.contains("apikey", ignoreCase = true) ||
                it.contains("authorization", ignoreCase = true)
        } == true)
        // normalizedArguments 只含业务参数，不含 requestId/sessionId 元数据。
        assertEquals(setOf("enabled"), snapshot.normalizedArguments?.keys)
    }

    @Test
    fun `无目标时快照目标与参数为 null`() {
        val snapshot = EvaluationSnapshotProjector.project(
            facts.copy(
                state = AgentState.WAITING_USER,
                executionStatus = null,
                selectedTarget = null,
                normalizedArguments = null
            )
        )
        assertNull(snapshot.selectedTarget)
        assertNull(snapshot.normalizedArguments)
        assertEquals(EvaluationTerminalStatus.NEED_DIALOGUE, snapshot.terminalStatus)
    }
}
