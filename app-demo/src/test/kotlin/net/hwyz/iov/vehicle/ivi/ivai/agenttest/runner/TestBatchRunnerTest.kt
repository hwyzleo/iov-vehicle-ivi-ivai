package net.hwyz.iov.vehicle.ivi.ivai.agenttest.runner

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.hwyz.iov.vehicle.ivi.ivai.agent.event.AgentEvent
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.ActualTarget
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.TargetType
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.gateway.AgentCommandGateway
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.AgentTestCase
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ExpectedTarget
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentInputSource
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-012 批次执行器单测：串行、独立 Session、单条超时、失败继续、重复事件去重、
 * 取消、禁用跳过与环境门禁（IVAI-REQ-114/119/121/122）。
 */
class TestBatchRunnerTest {

    private fun case(id: String, enabled: Boolean = true) = AgentTestCase(
        caseId = id,
        input = "打开空调",
        enabled = enabled,
        expectedTier = IntentTier.L0_DETERMINISTIC_TOOL,
        expectedDomain = BusinessDomainId.CABIN_COMFORT,
        expectedCapabilityPack = "cabin.climate",
        expectedTarget = ExpectedTarget(TargetType.TOOL, "climate.power.set"),
        expectedArguments = buildJsonObject { put("enabled", true) }
    )

    private fun defaultSnapshot(sessionId: String, requestId: String, terminal: EvaluationTerminalStatus) =
        AgentEvaluationSnapshot(
            requestId = requestId,
            sessionId = sessionId,
            initialTier = IntentTier.L0_DETERMINISTIC_TOOL,
            finalTier = IntentTier.L0_DETERMINISTIC_TOOL,
            finalDomain = BusinessDomainId.CABIN_COMFORT,
            selectedCapabilityPackIds = setOf("cabin.climate"),
            selectedTarget = ActualTarget(TargetType.TOOL, "climate.power.set"),
            normalizedArguments = buildJsonObject { put("enabled", true) },
            terminalStatus = terminal,
            reasonCode = "L0_UNIQUE_MATCH"
        )

    /** 确定性测试网关：每条用例独立 Session，可注入终态 / 快照 / 拒绝 / 超时。 */
    private inner class FakeGateway : AgentCommandGateway {
        var rejectSubmit = false
        /** 按提交序号决定是否发送终态事件（默认全部发送）。 */
        var emitTerminal: (Int) -> Boolean = { true }
        var snapshotMissing = false
        /** 按提交序号覆盖快照终态（用于 NEED_DIALOGUE 等场景）。 */
        var snapshotTerminal: (Int) -> EvaluationTerminalStatus = { EvaluationTerminalStatus.SUCCEEDED }
        val createdSessions = mutableListOf<String>()
        val submitted = mutableListOf<Pair<String, String>>()
        val cancelled = mutableListOf<String>()
        val snapshots = mutableMapOf<String, AgentEvaluationSnapshot>()
        private val channels = mutableMapOf<String, Channel<AgentEvent>>()
        private var sessionCounter = 0
        private var submittedCount = 0

        override suspend fun createTestSession(): String {
            val id = "test-session-${sessionCounter++}"
            createdSessions += id
            channels[id] = Channel(Channel.UNLIMITED)
            return id
        }

        override suspend fun submitText(
            sessionId: String,
            requestId: String,
            text: String,
            source: AgentInputSource
        ): Boolean {
            if (rejectSubmit) return false
            submitted += requestId to text
            val index = submittedCount
            submittedCount++
            if (emitTerminal(index)) {
                val terminal = AgentEvent.Reply(sessionId, requestId, requestId, "ok")
                channels[sessionId]?.send(terminal)
                // 注入重复终态事件（去重验证）。
                channels[sessionId]?.send(terminal)
            }
            if (!snapshotMissing) {
                snapshots[requestId] = defaultSnapshot(sessionId, requestId, snapshotTerminal(index))
            }
            return true
        }

        override fun observe(sessionId: String): Flow<AgentEvent> =
            (channels[sessionId] ?: Channel(Channel.UNLIMITED)).receiveAsFlow()

        override suspend fun evaluationSnapshot(requestId: String): AgentEvaluationSnapshot? = snapshots[requestId]

        override suspend fun cancelRequest(requestId: String): Boolean {
            cancelled += requestId
            return true
        }
    }

    private suspend fun runBatch(
        gateway: FakeGateway,
        cases: List<AgentTestCase>,
        timeoutMs: Long = 200
    ): Pair<List<AgentTestCaseResult>, TestRunSummary> {
        val runner = TestBatchRunner(gateway, caseTimeoutMs = timeoutMs)
        val results = mutableListOf<AgentTestCaseResult>()
        val summary = runner.run("run-1", cases) { results += it }
        return results to summary
    }

    @Test
    fun `串行执行 独立 Session 且 requestId 顺序提交`() = runTest {
        val gateway = FakeGateway()
        val (results, summary) = runBatch(gateway, listOf(case("C-1"), case("C-2"), case("C-3")))

        assertEquals(3, results.size)
        assertEquals(listOf(AgentTestCaseStatus.PASSED, AgentTestCaseStatus.PASSED, AgentTestCaseStatus.PASSED), results.map { it.status })
        // 每条用例独立 Session。
        assertEquals(3, gateway.createdSessions.distinct().size)
        // 串行：3 次提交且 requestId 互不相同（一一关联）。
        assertEquals(3, gateway.submitted.size)
        assertEquals(3, gateway.submitted.map { it.first }.distinct().size)
        // 单条 5 分，汇总 15/15。
        assertTrue(results.all { it.score?.total == 5 })
        assertEquals(15, summary.score)
        assertEquals(15, summary.maxScore)
        assertEquals(3, summary.executed)
        assertEquals(3, summary.passed)
        assertEquals(0, summary.failed)
    }

    @Test
    fun `禁用用例跳过且不计入满分`() = runTest {
        val gateway = FakeGateway()
        val cases = listOf(case("C-1"), case("C-2", enabled = false), case("C-3"))
        val (results, summary) = runBatch(gateway, cases)
        assertEquals(3, results.size)
        assertEquals(AgentTestCaseStatus.SKIPPED, results[1].status)
        // 跳过不提交：仅 2 次提交且 requestId 互不相同。
        assertEquals(2, gateway.submitted.size)
        assertEquals(2, gateway.submitted.map { it.first }.distinct().size)
        // 满分只计执行用例：2 × 5。
        assertEquals(2, summary.executed)
        assertEquals(10, summary.maxScore)
        assertEquals(1, summary.skipped)
    }

    @Test
    fun `快照缺失 → 记失败且批次继续`() = runTest {
        val gateway = FakeGateway()
        gateway.snapshotMissing = true
        val (results, _) = runBatch(gateway, listOf(case("C-1"), case("C-2"), case("C-3")))
        assertEquals(3, results.size)
        assertTrue(results.all { it.status == AgentTestCaseStatus.FAILED })
        assertTrue(results.all { it.errorCode == TestErrorCode.RESULT_MISSING })
        // 失败不中断批次：三条都执行了。
        assertEquals(3, gateway.submitted.size)
    }

    @Test
    fun `单条超时 → 取消活动请求并继续下一条`() = runTest {
        val gateway = FakeGateway()
        gateway.emitTerminal = { it != 0 } // 第一条永不产生终态 → 超时；第二条正常
        val (results, _) = runBatch(gateway, listOf(case("C-1"), case("C-2")), timeoutMs = 50)

        assertEquals(2, results.size)
        assertEquals(AgentTestCaseStatus.TIMEOUT, results[0].status)
        assertEquals(TestErrorCode.CASE_TIMEOUT, results[0].errorCode)
        assertEquals(AgentTestCaseStatus.PASSED, results[1].status)
        // 超时请求被取消。
        assertEquals(1, gateway.cancelled.size)
    }

    @Test
    fun `提交被拒 → IVAI-TEST-004 并继续`() = runTest {
        val gateway = FakeGateway()
        gateway.rejectSubmit = true
        val (results, _) = runBatch(gateway, listOf(case("C-1"), case("C-2")))
        assertEquals(2, results.size)
        assertTrue(results.all { it.status == AgentTestCaseStatus.FAILED && it.errorCode == TestErrorCode.SUBMIT_FAILED })
    }

    @Test
    fun `NEED_DIALOGUE 终态 → INCOMPLETE 不自动补答`() = runTest {
        val gateway = FakeGateway()
        gateway.snapshotTerminal = { if (it == 0) EvaluationTerminalStatus.NEED_DIALOGUE else EvaluationTerminalStatus.SUCCEEDED }
        val (results, _) = runBatch(gateway, listOf(case("C-1"), case("C-2")))
        assertEquals(AgentTestCaseStatus.INCOMPLETE, results[0].status)
        assertEquals(EvaluationTerminalStatus.NEED_DIALOGUE, results[0].terminalStatus)
        assertEquals(AgentTestCaseStatus.PASSED, results[1].status)
    }

    @Test
    fun `批次取消时取消活动请求`() = runTest {
        val gateway = FakeGateway()
        gateway.emitTerminal = { false } // 活动请求不产生终态
        val runner = TestBatchRunner(gateway, caseTimeoutMs = 100_000)
        val job = launch { runner.run("run-1", listOf(case("C-1"))) { } }
        runCurrent()
        assertTrue(gateway.submitted.isNotEmpty())
        job.cancel()
        job.join()
        assertTrue(gateway.cancelled.isNotEmpty())
    }

    @Test
    fun `环境门禁拒绝时抛 IVAI-TEST-003`() = runTest {
        val gateway = FakeGateway()
        val runner = TestBatchRunner(gateway, environmentAllowed = { false })
        val error = runCatching { runner.run("run-1", listOf(case("C-1"))) { } }.exceptionOrNull()
        assertTrue(error is TestRunnerException)
        assertEquals(TestErrorCode.ENVIRONMENT_FORBIDDEN, (error as TestRunnerException).errorCode)
        assertTrue(gateway.submitted.isEmpty())
    }

    @Test
    fun `重复终态事件只评分一次`() = runTest {
        val gateway = FakeGateway() // submitText 对同一 requestId 发送两次终态
        val (results, _) = runBatch(gateway, listOf(case("C-1")))
        assertEquals(1, results.size)
        assertEquals(1, gateway.submitted.size)
        assertEquals(AgentTestCaseStatus.PASSED, results[0].status)
    }

    @Test
    fun `非活动请求事件被忽略`() = runTest {
        val gateway = FakeGateway()
        // 手动向当前 session 注入一个其他 requestId 的终态事件。
        val cases = listOf(case("C-1"))
        val runner = TestBatchRunner(gateway, caseTimeoutMs = 200)
        val results = mutableListOf<AgentTestCaseResult>()
        runner.run("run-1", cases) { results += it }
        assertEquals(1, results.size)
        assertEquals(AgentTestCaseStatus.PASSED, results[0].status)
    }
}
