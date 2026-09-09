package net.hwyz.iov.vehicle.ivi.ivai.agenttest.result

import net.hwyz.iov.vehicle.ivi.ivai.agenttest.timing.TestCaseTiming

/**
 * 单条用例终态（IVI-IVAI-DSN-CR-014 批次状态）。PENDING/RUNNING 非终态；
 * PASSED、FAILED、REJECTED、TIMED_OUT、CANCELLED、ERROR 均属于终态。
 */
enum class TestCaseStatus {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,
    REJECTED,
    TIMED_OUT,
    CANCELLED,
    ERROR,
    /** CR-012 兼容扩展：未执行（enabled=false）的用例，视为已定案（非 PENDING/RUNNING）。 */
    SKIPPED;

    val isTerminal: Boolean
        get() = this != PENDING && this != RUNNING
}

/**
 * 期望意图（IVI-IVAI-DSN-CR-014）。[targetId] 统一承载 canonical Tool ID 或
 * Workflow ID；展示与导出列名保持「工具或工作流」。
 */
data class IntentExpectation(
    val level: String? = null,
    val domainId: String? = null,
    val capabilityPackId: String? = null,
    val targetId: String? = null,
    val arguments: Map<String, Any?> = emptyMap()
)

/**
 * 实际意图（IVI-IVAI-DSN-CR-014）。从终态结构化快照投影，不从自然语言回复推断。
 */
data class IntentActualResult(
    val level: String? = null,
    val domainId: String? = null,
    val capabilityPackId: String? = null,
    val targetId: String? = null,
    val arguments: Map<String, Any?> = emptyMap()
)

/**
 * 不可变的用例执行结果（IVI-IVAI-DSN-CR-014 执行结果模型）。
 *
 * 结果对象创建后不可被 UI 二次计算或覆盖；导出数据来自本快照，不从页面渲染文本
 * 反向解析。同一批次每个 caseId 只导出一行。
 */
data class TestCaseExecutionResult(
    val batchId: String,
    val caseId: String,
    val input: String,
    val expected: IntentExpectation,
    val actual: IntentActualResult?,
    val status: TestCaseStatus,
    val timing: TestCaseTiming,
    val failureReason: String? = null,
    /** CR-018：运行时失败与评分差异拆分诊断（可选列导出）。 */
    val diagnostics: net.hwyz.iov.vehicle.ivi.ivai.agenttest.diagnostics.TestFailureDiagnostics? = null
)

/**
 * 测试批次状态（IVI-IVAI-DSN-CR-014 批次状态与页面行为）。
 * 仅当批次非空且全部用例进入终态时才允许导出。
 */
data class TestBatchState(
    val batchId: String,
    val cases: List<TestCaseExecutionResult> = emptyList(),
    val exportState: ExportState = ExportState.DISABLED
) {
    val allTerminal: Boolean
        get() = cases.isNotEmpty() && cases.all { it.status.isTerminal }
}

/** 导出状态（页面行为：禁用 / 可导出 / 生成中 / 成功 / 失败）。 */
enum class ExportState {
    DISABLED,
    ENABLED,
    EXPORTING,
    EXPORTED,
    FAILED
}
