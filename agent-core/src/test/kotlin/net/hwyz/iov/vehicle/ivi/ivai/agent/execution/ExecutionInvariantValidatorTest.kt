package net.hwyz.iov.vehicle.ivi.ivai.agent.execution

import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.ActualTarget
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.TargetType
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-016 单元测试：L1 状态不变量（ExecutionInvariantValidator）。
 *
 * 覆盖设计测试设计：
 *  - L1 需要模型但未出现 MODEL_REQUEST_STARTED 时不变量失败；
 *  - L1 候选为空时生成明确终态和 reasonCode（合法追问/拒绝/失败分支）；
 *  - 非成功终态必须具有 terminalStage 和 reasonCode。
 */
class ExecutionInvariantValidatorTest {

    private val validator = DefaultExecutionInvariantValidator()

    private fun context(
        finalTier: IntentTier? = IntentTier.L1_LOCAL_TOOL_REASONING,
        llmInvoked: Boolean = true,
        modelRequestCount: Int = 1,
        terminalStatus: EvaluationTerminalStatus = EvaluationTerminalStatus.SUCCEEDED,
        terminalStage: TerminalStage? = TerminalStage.EXECUTION,
        reasonCode: String? = "L1_TOOL_DOMAIN",
        selectedTarget: ActualTarget? = ActualTarget(TargetType.TOOL, "climate.fan.speed.set")
    ) = ExecutionInvariantContext(
        finalTier = finalTier,
        llmInvoked = llmInvoked,
        modelRequestCount = modelRequestCount,
        terminalStatus = terminalStatus,
        terminalStage = terminalStage,
        reasonCode = reasonCode,
        selectedTarget = selectedTarget
    )

    @Test
    fun `L1 需要模型但未发起模型请求时不变量失败`() {
        val result = validator.validate(
            context(llmInvoked = false, modelRequestCount = 0)
        )
        assertInstanceOf(InvariantResult.Violated::class.java, result)
        val violated = result as InvariantResult.Violated
        assertEquals("IVAI-STATE-001", violated.errorCode)
    }

    @Test
    fun `L1 成功终态但未调用 LLM 违反不变量`() {
        val result = validator.validate(
            context(
                terminalStatus = EvaluationTerminalStatus.SUCCEEDED,
                llmInvoked = false,
                modelRequestCount = 0
            )
        )
        assertInstanceOf(InvariantResult.Violated::class.java, result)
    }

    @Test
    fun `L1 追问终态且未调用 LLM 合法（NEED_DIALOGUE 带原因）`() {
        val result = validator.validate(
            context(
                terminalStatus = EvaluationTerminalStatus.NEED_DIALOGUE,
                terminalStage = TerminalStage.CANDIDATE_BOUNDARY,
                reasonCode = "L0_MISSING_ARGUMENTS",
                llmInvoked = false,
                modelRequestCount = 0
            )
        )
        assertEquals(InvariantResult.Pass, result)
    }

    @Test
    fun `L1 拒绝终态未调用 LLM 合法（REJECTED 带原因）`() {
        val result = validator.validate(
            context(
                terminalStatus = EvaluationTerminalStatus.REJECTED,
                terminalStage = TerminalStage.POLICY,
                reasonCode = "REJECT_UNSUPPORTED",
                llmInvoked = false,
                modelRequestCount = 0
            )
        )
        assertEquals(InvariantResult.Pass, result)
    }

    @Test
    fun `非成功终态缺少 terminalStage 或 reasonCode 违反不变量`() {
        val result = validator.validate(
            context(
                terminalStatus = EvaluationTerminalStatus.FAILED,
                terminalStage = null,
                reasonCode = null
            )
        )
        assertInstanceOf(InvariantResult.Violated::class.java, result)
        assertEquals("IVAI-STATE-001", (result as InvariantResult.Violated).errorCode)
    }

    @Test
    fun `非成功终态带 terminalStage 与 reasonCode 通过`() {
        val result = validator.validate(
            context(
                terminalStatus = EvaluationTerminalStatus.TIMEOUT,
                terminalStage = TerminalStage.MODEL_REQUEST,
                reasonCode = "IVAI-MODEL-TIMEOUT-004"
            )
        )
        assertEquals(InvariantResult.Pass, result)
    }

    @Test
    fun `llmInvoked 与模型请求计数不一致违反不变量`() {
        val result = validator.validate(
            context(llmInvoked = true, modelRequestCount = 0)
        )
        assertInstanceOf(InvariantResult.Violated::class.java, result)
    }

    @Test
    fun `L0 路径不要求模型请求`() {
        val result = validator.validate(
            context(
                finalTier = IntentTier.L0_DETERMINISTIC_TOOL,
                llmInvoked = false,
                modelRequestCount = 0,
                terminalStatus = EvaluationTerminalStatus.SUCCEEDED,
                terminalStage = TerminalStage.SUCCESS,
                reasonCode = "L0_UNIQUE_MATCH"
            )
        )
        assertEquals(InvariantResult.Pass, result)
    }

    @Test
    fun `成功终态合法（LLM 已调用且带终止阶段）`() {
        val result = validator.validate(context())
        assertTrue(result is InvariantResult.Pass, "成功终态 + LLM 调用 + 带原因应通过不变量")
    }
}
