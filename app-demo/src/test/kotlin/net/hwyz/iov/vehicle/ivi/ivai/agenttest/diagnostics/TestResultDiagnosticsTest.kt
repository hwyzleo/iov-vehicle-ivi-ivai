package net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics

import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.EvaluationTerminalStatus
import net.hwyz.iov.vehicle.ivi.ivai.agent.execution.TerminalStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-018 验证设计 · 运行时失败与评分差异拆分诊断：
 *  - 运行时成功但评分不匹配只记 score mismatch，不伪装为运行时失败；
 *  - 无法区分时置 IVAI-SCORE-DIAG-001；
 *  - 终态 → RuntimeStatus 映射完整。
 */
class TestResultDiagnosticsTest {

    @Test
    fun `运行时成功但评分失败为 score mismatch 而非运行时失败`() {
        val diag = TestFailureDiagnostics(
            runtimeStatus = RuntimeStatus.SUCCEEDED,
            scoreStatus = ScoreStatus.FAILED,
            mismatchDimensions = setOf(ScoreDimension.TARGET)
        )
        assertTrue(diag.isScoreMismatchOnly)
        assertFalse(diag.indistinguishable)
    }

    @Test
    fun `运行时失败且评分失败可区分`() {
        val diag = TestFailureDiagnostics(
            runtimeStatus = RuntimeStatus.FAILED,
            runtimeTerminalStage = TerminalStage.MODEL_REQUEST,
            runtimeReasonCode = "IVAI-MODEL-TIMEOUT-004",
            scoreStatus = ScoreStatus.FAILED,
            mismatchDimensions = setOf(ScoreDimension.TIER, ScoreDimension.TARGET)
        )
        assertFalse(diag.isScoreMismatchOnly)
        assertFalse(diag.indistinguishable)
        assertEquals(TerminalStage.MODEL_REQUEST, diag.runtimeTerminalStage)
    }

    @Test
    fun `快照与评分均缺失无法区分时置 IVAI-SCORE-DIAG-001`() {
        val diag = TestFailureDiagnostics(
            runtimeStatus = RuntimeStatus.UNKNOWN,
            scoreStatus = ScoreStatus.FAILED
        )
        assertTrue(diag.indistinguishable)
        assertEquals(TestFailureDiagnostics.SCORE_DIAG_CODE, "IVAI-SCORE-DIAG-001")
    }

    @Test
    fun `终态映射 RuntimeStatus 完整`() {
        assertEquals(RuntimeStatus.SUCCEEDED, TestResultDiagnostics.runtimeStatusOf(EvaluationTerminalStatus.SUCCEEDED))
        assertEquals(RuntimeStatus.REPLIED, TestResultDiagnostics.runtimeStatusOf(EvaluationTerminalStatus.REPLIED))
        assertEquals(RuntimeStatus.REJECTED, TestResultDiagnostics.runtimeStatusOf(EvaluationTerminalStatus.REJECTED))
        assertEquals(RuntimeStatus.NEED_DIALOGUE, TestResultDiagnostics.runtimeStatusOf(EvaluationTerminalStatus.NEED_DIALOGUE))
        assertEquals(RuntimeStatus.WAITING_CONFIRMATION, TestResultDiagnostics.runtimeStatusOf(EvaluationTerminalStatus.WAITING_CONFIRMATION))
        assertEquals(RuntimeStatus.FAILED, TestResultDiagnostics.runtimeStatusOf(EvaluationTerminalStatus.FAILED))
        assertEquals(RuntimeStatus.TIMEOUT, TestResultDiagnostics.runtimeStatusOf(EvaluationTerminalStatus.TIMEOUT))
        assertEquals(RuntimeStatus.CANCELLED, TestResultDiagnostics.runtimeStatusOf(EvaluationTerminalStatus.CANCELLED))
        assertEquals(RuntimeStatus.UNKNOWN, TestResultDiagnostics.runtimeStatusOf(null))
    }
}
