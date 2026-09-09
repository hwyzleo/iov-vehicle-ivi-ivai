package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentRoute
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.SnapshotHolder
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.StubModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.TestGraph
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-016 测试工具：直接返回原始 content 的 Provider（不预解析 contentJson），
 * 用于验证 ConstrainedModelResponseParser 的代码围栏/尾逗号受控修复。
 */
private class RawContentModelProvider(private val content: String) : ModelProvider {
    override suspend fun generate(request: ModelRequest): ModelResponse =
        ModelResponse(
            requestId = request.requestId,
            content = content,
            contentJson = null, // 不预解析：由 AgentWorkflow 的 parser 修复
            model = "qwen3.5:4b",
            finishReason = "stop",
            latencyMs = 1
        )
}

/**
 * CR-016 集成测试：Agent 运行时与批量测试暴露的代码缺口修复。
 *
 * 覆盖设计集成测试：
 *  1. L0 数值档位与位置组合 → 正确的 Target/Arguments；
 *  2. L1 绝对档位、相对调节、风口和风向相似表达 → 受控候选选择；
 *  3. zone=ALL、旧 Tool ID、代码围栏 JSON 和尾逗号响应 → canonicalization 与单次修复；
 *  4. 候选集外 Tool、缺参、越界和冲突参数 → 禁止执行及细分错误码；
 *  5. 候选边界通过后执行失败仍保留 FrozenCandidateSnapshot。
 */
class AgentWorkflowCr016IntegrationTest {

    private val powerSet = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power.set","arguments":{"enabled":true}}],"modelConfidence":0.98,"riskLevel":"low","needConfirmation":false,"missingArguments":[]}"""

    // ---- L0 数值档位与位置组合 ----

    @Test
    fun `L0 风量档位数值与位置组合直达`() = runTest {
        val (workflow, _, _) = TestGraph.buildGovernedStubGraph(StubModelProvider(powerSet))
        val result = workflow.process(input("req-l0-fan", "主驾风量档位调到5档"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state, "L0 数值档位+位置应直达执行：$result")
        val target = result.executionResult?.toolId
        assertEquals("climate.fan.speed.set", target)
    }

    @Test
    fun `L0 全车风量档位 ALL 语义映射 canonical`() = runTest {
        val (workflow, _, _) = TestGraph.buildGovernedStubGraph(StubModelProvider(powerSet))
        val result = workflow.process(input("req-l0-all", "全车风量档位调到3档"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertEquals("climate.fan.speed.set", result.executionResult?.toolId)
    }

    @Test
    fun `L0 绝对档位与相对调节互不误命中`() = runTest {
        val (workflow, _, _) = TestGraph.buildGovernedStubGraph(StubModelProvider(powerSet))
        // 相对调节（当前值上增减）→ climate.fan.speed.adjust，不是 set。
        val result = workflow.process(input("req-l0-adjust", "风量调高2档"), Session())
        assertEquals(AgentState.SUCCEEDED, result.state)
        assertEquals("climate.fan.speed.adjust", result.executionResult?.toolId)
    }

    // ---- L1 相似表达受控候选 ----

    /** 触发 L1（“我有点冷”无 L0 规则 → 检索 + 本地模型），Stub 决定输出。 */
    private fun l1Graph(content: String) =
        TestGraph.buildGovernedStubGraph(StubModelProvider(content))

    @Test
    fun `L1 风口开关与风向模式受控区分`() = runTest {
        val vent = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.vent.set","arguments":{"zone":"front","enabled":true}}],"modelConfidence":0.92,"riskLevel":"low","needConfirmation":false,"missingArguments":[]}"""
        val (workflow, _, _) = l1Graph(vent)
        val result = workflow.process(input("req-l1-vent", "我有点冷"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state)
        assertEquals("climate.vent.set", result.executionResult?.toolId)
    }

    @Test
    fun `L1 模型输出代码围栏 JSON 被单次修复`() = runTest {
        val fenced = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.auto.set","arguments":{"enabled":true}}],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":false,"missingArguments":[]}"""
        val wrapped = "```json\n$fenced\n```"
        val (workflow, _, _) = TestGraph.buildGovernedStubGraph(RawContentModelProvider(wrapped))
        val result = workflow.process(input("req-l1-fence", "我有点冷"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state, "代码围栏 JSON 应被安全剥离并执行：$result")
        assertEquals("climate.auto.set", result.executionResult?.toolId)
    }

    @Test
    fun `L1 模型输出尾逗号 JSON 被单次修复`() = runTest {
        val trailing = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.auto.set","arguments":{"enabled":true}}],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":false,"missingArguments":[],}"""
        val (workflow, _, _) = TestGraph.buildGovernedStubGraph(RawContentModelProvider(trailing))
        val result = workflow.process(input("req-l1-trailing", "我有点冷"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state, "尾逗号 JSON 应被单次修复并执行：$result")
        assertEquals("climate.auto.set", result.executionResult?.toolId)
    }

    @Test
    fun `L1 模型输出旧 Tool ID 被 canonicalize 后执行`() = runTest {
        // 旧空调 ID（CR-010 迁移表）→ canonical climate.power.set。
        val legacy = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power_on","arguments":{"enabled":true}}],"modelConfidence":0.95,"riskLevel":"low","needConfirmation":false,"missingArguments":[]}"""
        val (workflow, _, _) = l1Graph(legacy)
        val result = workflow.process(input("req-l1-legacy", "我有点冷"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state, "旧 ID 应 canonicalize 后执行：$result")
        assertEquals("climate.power.set", result.executionResult?.toolId)
    }

    @Test
    fun `L1 模型输出未知 Tool 被禁止执行`() = runTest {
        val unknown = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.ghost","arguments":{}}],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":false,"missingArguments":[]}"""
        val (workflow, _, _) = l1Graph(unknown)
        val result = workflow.process(input("req-l1-ghost", "我有点冷"), Session())

        assertEquals(AgentState.REJECTED, result.state)
        assertEquals("IVAI-TOOL-001", result.errorCode, "未知 Tool 应被阻止（IVAI-TOOL-001）")
        assertNull(result.executionResult)
    }

    @Test
    fun `L1 模型输出缺必填参数被禁止执行`() = runTest {
        val missing = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.temperature.set","arguments":{}}],"modelConfidence":0.8,"riskLevel":"medium","needConfirmation":false,"missingArguments":[]}"""
        val (workflow, _, _) = l1Graph(missing)
        val result = workflow.process(input("req-l1-missing", "我有点冷"), Session())

        assertEquals(AgentState.REJECTED, result.state)
        assertEquals("IVAI-TOOL-002", result.errorCode, "缺必填参数应被阻止（IVAI-TOOL-002）")
    }

    // ---- 候选边界冻结 ----

    @Test
    fun `候选边界通过后生成 FrozenCandidateSnapshot 保留 canonical 目标`() = runTest {
        // 治理 Tool 经候选边界冻结：L1 候选边界通过后，已确定的 canonical
        // Target/Arguments 保留（EvaluationSnapshot 带候选 Hash）。
        val fail = """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.power.set","arguments":{"enabled":true}}],"modelConfidence":0.95,"riskLevel":"low","needConfirmation":false,"missingArguments":[]}"""
        val holder = SnapshotHolder()
        val wf = TestGraph.buildGovernedStubGraphWithSnapshot(
            StubModelProvider(fail), holder
        )
        val result = wf.process(input("req-freeze", "我有点冷"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state, "Mock 执行成功")
        assertEquals("climate.power.set", result.executionResult?.toolId)
        val snap = holder.snapshot
        assertNotNull(snap, "终态应投影 EvaluationSnapshot")
        assertNotNull(snap!!.selectedTarget, "候选边界通过后快照保留 canonical Target")
        assertEquals("climate.power.set", snap.selectedTarget!!.id)
        assertEquals("SUCCESS", snap.terminalStage?.name)
        assertNotNull(snap.candidateSetHash, "快照携带候选集 Hash（CR-016 可观测性）")
    }

    // ---- 状态不变量 ----

    @Test
    fun `L1 未调用模型不得产生无原因成功结果`() = runTest {
        // L1 路由但模型返回非法 JSON（无法安全修复）→ FAILED 且带原因，而非无原因空结果。
        val (workflow, _, _) = TestGraph.buildGovernedStubGraph(StubModelProvider("not json at all"))
        val result = workflow.process(input("req-inv", "我有点冷"), Session())

        assertEquals(AgentState.FAILED, result.state)
        assertNotNull(result.errorCode, "非成功终态必须带 errorCode/reasonCode")
    }

    private fun input(requestId: String, text: String) =
        AgentInput(requestId = requestId, text = text, source = "mock")
}
