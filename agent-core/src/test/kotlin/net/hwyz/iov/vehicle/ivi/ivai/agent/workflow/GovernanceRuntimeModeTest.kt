package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.StubModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.TestGraph
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceRuntimeMode
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.RuntimeEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-010 验证设计 · 开发桩隔离（STRICT vs DEVELOPMENT_STUB）。
 *
 *  - STRICT：所有发布构建的唯一模式；DRAFT / 豁免不得进入统一候选集，硬动作
 *    因无可用 Pack 被拒绝（IVAI-CAP-001）。Release 门禁检测到开发桩即失败
 *    （IVAI-GOV-004）。
 *  - DEVELOPMENT_STUB：仅 debug/test/mock-vehicle；白名单 DRAFT 仅经 Mock
 *    Adapter 参与 L0/L1 骨架验证，DRAFT 状态保留。
 */
class GovernanceRuntimeModeTest {

    private fun input(requestId: String, text: String, turnId: String = requestId) =
        AgentInput(requestId = requestId, text = text, source = "mock", turnId = turnId)

    @Test
    fun `STRICT 模式 DRAFT 资产不进入候选 硬动作被 IVAI-CAP-001 拒绝`() = runTest {
        val stub = StubModelProvider()
        val (workflow, adapter, _) = TestGraph.buildGovernedStubGraph(
            stub,
            environment = RuntimeEnvironment(mode = GovernanceRuntimeMode.STRICT)
        )
        val result = workflow.process(input("req-strict", "打开空调", turnId = "turn-strict"), Session())
        // 全部 160 Tool 为 DRAFT → STRICT 无候选 → 硬动作拒绝（IVAI-CAP-001）。
        assertEquals(AgentState.REJECTED, result.state)
        assertEquals("IVAI-CAP-001", result.errorCode)
        assertTrue(adapter.executedMethods().isEmpty(), "STRICT 不得执行 DRAFT 桩")
    }

    @Test
    fun `DEVELOPMENT_STUB 模式白名单 DRAFT 经 Mock Adapter 执行`() = runTest {
        val stub = StubModelProvider()
        val (workflow, adapter, _) = TestGraph.buildGovernedStubGraph(
            stub,
            environment = GovernanceWorkspace.devStubEnvironment()
        )
        // “打开空调”确定性唯一匹配 climate.power.set → L0，不创建模型请求。
        val result = workflow.process(input("req-stub", "打开空调", turnId = "turn-stub"), Session())
        assertEquals(AgentState.SUCCEEDED, result.state, "STUB 下 canonical 空调 Tool 应经 Mock 执行：${result.errorCode}")
        assertEquals("climate.power.set", adapter.lastMethodId)
        assertEquals(true, adapter.lastArguments["enabled"])
    }

    @Test
    fun `Release 门禁拒绝非 STRICT 环境与开发桩豁免`() {
        // 非 STRICT → IVAI-GOV-004。
        val e1 = assertThrows(IllegalArgumentException::class.java) {
            GovernanceWorkspace.assertReleaseReady(GovernanceWorkspace.devStubEnvironment())
        }
        assertTrue(e1.message!!.contains("IVAI-GOV-004"))
        // STRICT 但携带豁免 → IVAI-GOV-004。
        val e2 = assertThrows(IllegalArgumentException::class.java) {
            GovernanceWorkspace.assertReleaseReady(
                RuntimeEnvironment(
                    mode = GovernanceRuntimeMode.STRICT,
                    stubExemptions = listOf(GovernanceWorkspace.devStubExemption())
                )
            )
        }
        assertTrue(e2.message!!.contains("IVAI-GOV-004"))
        // STRICT + 无豁免 → 通过并返回目录。
        GovernanceWorkspace.assertReleaseReady(RuntimeEnvironment(mode = GovernanceRuntimeMode.STRICT))
    }
}
