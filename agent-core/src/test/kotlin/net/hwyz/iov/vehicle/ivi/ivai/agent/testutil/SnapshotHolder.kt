package net.hwyz.iov.vehicle.ivi.ivai.agent.testutil

import net.hwyz.iov.vehicle.ivi.ivai.agent.evaluation.AgentEvaluationSnapshot

/**
 * CR-016 测试工具：捕获终态 EvaluationSnapshot（候选边界冻结 / 不变量校验断言）。
 */
class SnapshotHolder {
    @Volatile
    var snapshot: AgentEvaluationSnapshot? = null
}
