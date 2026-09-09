package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.CandidateSetSource
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.IntentTier
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.NormalizedInput
import net.hwyz.iov.vehicle.ivi.ivai.agent.router.ToolCandidateSet
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.SnapshotHolder
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.StubModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.TestGraph
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.PositionAliasResolver
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.aliases.VehicleCabinTopology
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.Cr017ErrorCodes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-017 集成测试：位置 Alias / Tool RAG 语义 / L1 调度不变量。
 *
 * 覆盖设计集成验收：
 *  - L1 中左/中右/2排/3排 → 模型选择 + canonical 参数执行；
 *  - 模型输出 ALL/ROW2/ZONE2/3RD 批准 Alias → canonical 转换后执行；
 *  - 模型输出 right / rear 替代明确排数 → 歧义阻止（不得静默执行）；
 *  - L1 候选为空 → IVAI-CANDIDATE-001（不调用模型、明确 reasonCode）；
 *  - 车型拓扑不适用（两排车 + 3排）→ IVAI-ALIAS-TOPOLOGY-001；
 *  - EvaluationSnapshot 导出 retrievalInvoked / retrievedCandidateCount /
 *    modelDispatchAttempted（REQ-183）。
 */
class AgentWorkflowCr017IntegrationTest {

    private fun input(requestId: String, text: String) = AgentInput(
        requestId = requestId,
        text = text,
        source = "text"
    )

    /** 模型选择 climate.fan.speed.set 并输出指定 zone 的响应。 */
    private fun fanSetResponse(zone: String): String =
        """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.fan.speed.set","arguments":{"zone":"$zone","level":5}}],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":false,"missingArguments":[]}"""

    // ---- L1 位置表达：模型选择 + canonical 参数 ----

    @Test
    fun `L1 中左表达 模型输出 middle_left 执行成功`() = runTest {
        val holder = SnapshotHolder()
        val workflow = TestGraph.buildGovernedStubGraphCr017(
            StubModelProvider(fanSetResponse("middle_left")),
            holder = holder
        )
        val result = workflow.process(input("cr017-ml", "中左的风量帮我开到5档"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state, "L1 中左应执行成功: $result")
        assertEquals("climate.fan.speed.set", result.executionResult?.toolId)
        // 快照可观测性（REQ-183）。
        val snapshot = holder.snapshot
        assertNotNull(snapshot)
        assertEquals(false, snapshot!!.retrievalInvoked, "ALL_ENABLED 候选源未执行向量检索")
        assertTrue((snapshot.retrievedCandidateCount ?: 0) > 0, "应导出检索候选数")
        assertEquals(true, snapshot.modelDispatchAttempted, "L1 需要模型时应发起模型分发")
        assertEquals(true, snapshot.llmInvoked)
    }

    @Test
    fun `L1 2排表达 模型输出 ROW2 转换为 second_row`() = runTest {
        val workflow = TestGraph.buildGovernedStubGraphCr017(StubModelProvider(fanSetResponse("ROW2")))
        val result = workflow.process(input("cr017-2row", "2排的风量帮我开到5档"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state, "ROW2 应经 canonicalizer 转换为 second_row: $result")
        // 执行参数为 canonical（Mock 适配器按参数执行）。
        assertEquals("climate.fan.speed.set", result.executionResult?.toolId)
    }

    @Test
    fun `L1 3排表达 模型输出 3RD 转换为 third_row`() = runTest {
        val workflow = TestGraph.buildGovernedStubGraphCr017(StubModelProvider(fanSetResponse("3RD")))
        val result = workflow.process(input("cr017-3rd", "3排的风量帮我开到5档"), Session())
        assertEquals(AgentState.SUCCEEDED, result.state, "3RD 应转换为 third_row: $result")
    }

    @Test
    fun `L1 无位置表达 模型输出 ALL 转换为 all`() = runTest {
        val workflow = TestGraph.buildGovernedStubGraphCr017(StubModelProvider(fanSetResponse("ALL")))
        val result = workflow.process(input("cr017-all", "风量帮我开到5档"), Session())
        assertEquals(AgentState.SUCCEEDED, result.state, "ALL 应转换为 all: $result")
    }

    // ---- 歧义 / 冲突保护 ----

    @Test
    fun `模型输出 right 宽泛表达 歧义被阻止`() = runTest {
        val workflow = TestGraph.buildGovernedStubGraphCr017(
            StubModelProvider(fanSetResponse("middle_right"))
        )
        val result = workflow.process(input("cr017-right", "右边风量帮我开到5档"), Session())

        assertTrue(
            result.state == AgentState.REJECTED || result.state == AgentState.FAILED,
            "宽泛表达不得静默执行: $result"
        )
        assertNull(result.executionResult, "歧义位置不得产生执行目标")
    }

    @Test
    fun `模型以 rear 替代明确 2排 被阻止`() = runTest {
        val workflow = TestGraph.buildGovernedStubGraphCr017(StubModelProvider(fanSetResponse("rear")))
        val result = workflow.process(input("cr017-rear", "2排的风量帮我开到5档"), Session())

        assertTrue(
            result.state == AgentState.REJECTED || result.state == AgentState.FAILED,
            "rear 不得替代明确 second_row: $result"
        )
        assertNull(result.executionResult)
    }

    @Test
    fun `模型输出与用户明确位置证据冲突被阻止`() = runTest {
        val workflow = TestGraph.buildGovernedStubGraphCr017(StubModelProvider(fanSetResponse("all")))
        val result = workflow.process(input("cr017-conflict", "中左的风量帮我开到5档"), Session())
        assertTrue(
            result.state == AgentState.REJECTED || result.state == AgentState.FAILED,
            "模型输出 all 与用户明确 middle_left 冲突不得执行: $result"
        )
        assertNull(result.executionResult)
    }

    // ---- L1 调度不变量 ----

    @Test
    fun `L1 候选为空 输出 IVAI-CANDIDATE-001 且不调用模型`() = runTest {
        val holder = SnapshotHolder()
        val stub = StubModelProvider(fanSetResponse("all"))
        val emptyProvider = object : net.hwyz.iov.vehicle.ivi.ivai.agent.router.ToolCandidateProvider {
            override suspend fun candidates(
                input: NormalizedInput,
                context: net.hwyz.iov.vehicle.ivi.ivai.agent.router.AgentContext,
                ragSnapshot: net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagExecutionSnapshot,
                capabilitySnapshot: net.hwyz.iov.vehicle.ivi.ivai.agent.capability.CapabilitySnapshot?
            ): ToolCandidateSet = ToolCandidateSet(
                candidates = emptyList(),
                source = CandidateSetSource.ALL_ENABLED,
                topK = 0,
                fallbackReason = null
            )
        }
        val workflow = TestGraph.buildGovernedStubGraphCr017(stub, toolCandidateProvider = emptyProvider, holder = holder)
        val result = workflow.process(input("cr017-empty", "风量帮我开到5档"), Session())

        assertEquals(AgentState.FAILED, result.state, "候选为空应明确失败: $result")
        assertEquals(net.hwyz.iov.vehicle.ivi.ivai.agent.error.Cr016ErrorCodes.CANDIDATE_SET_EMPTY, result.errorCode)
        assertEquals(0, stub.requests, "候选为空不得发起模型请求")
        val snapshot = holder.snapshot
        assertNotNull(snapshot)
        assertEquals(false, snapshot!!.llmInvoked)
        assertNotNull(snapshot.reasonCode, "候选为空必须有明确 reasonCode")
        assertNotNull(snapshot.terminalStage, "候选为空必须有终止阶段")
    }

    @Test
    fun `两排车型 3排 输入 输出 IVAI-ALIAS-TOPOLOGY-001`() = runTest {
        val twoRow = VehicleCabinTopology.twoRow("demo-2row")
        val resolver = PositionAliasResolver(topologyResolver = { twoRow })
        val workflow = TestGraph.buildGovernedStubGraphCr017(
            StubModelProvider(fanSetResponse("third_row")),
            positionResolver = resolver,
            vehicleModel = "demo-2row"
        )
        val result = workflow.process(input("cr017-topo", "3排的风量帮我开到5档"), Session())

        assertTrue(
            result.state == AgentState.REJECTED || result.state == AgentState.FAILED,
            "3排 在两排车应被拒绝: $result"
        )
        assertEquals(Cr017ErrorCodes.ALIAS_TOPOLOGY, result.errorCode)
        assertNull(result.executionResult)
    }

    // ---- 风量回归：L0 批准不回归 + 无“L1 未调用LLM且无原因” ----------------

    /**
     * CR-017 风量回归验收：
     *  - 批准 L0 用例保持直达（不回归）；
     *  - 任意用例不得出现 finalTier=L1 且未调用 LLM 且无 reasonCode 的空结果
     *    （ExecutionInvariantValidator + L1 空候选分支保证）。
     */
    @Test
    fun `风量回归 L0 批准保持直达 且无 L1 未调用模型空结果`() = runTest {
        // 批准 L0 风量表达：应直达执行。
        val approvedL0 = listOf(
            "风量档位调到2档",
            "设置风量档位2档",
            "主驾风量档位调到5档",
            "全车风量档位调到3档"
        )
        val (workflowL0, _, _) = TestGraph.buildGovernedStubGraph(StubModelProvider(fanSetResponse("all")))
        for ((i, text) in approvedL0.withIndex()) {
            val r = workflowL0.process(input("l0-$i", text), Session())
            assertEquals(
                AgentState.SUCCEEDED, r.state, "批准 L0 应直达执行: $text -> $r"
            )
            assertEquals("climate.fan.speed.set", r.executionResult?.toolId)
        }

        // 混合 L1 风量表达（位置/宽泛/缺参边界）：无“L1 未调用 LLM 且无原因”空结果。
        val holder = SnapshotHolder()
        val workflow = TestGraph.buildGovernedStubGraphCr017(
            StubModelProvider(
                fanSetResponse("middle_left"), // 中左
                fanSetResponse("second_row"), // 2排
                fanSetResponse("third_row") // 3排
            ),
            holder = holder
        )
        val cases = listOf(
            "中左的风量帮我开到5档",
            "2排的风量帮我开到5档",
            "3排的风量帮我开到5档"
        )
        for ((i, text) in cases.withIndex()) {
            val r = workflow.process(input("l1-$i", text), Session())
            assertTrue(
                r.state == AgentState.SUCCEEDED || r.state == AgentState.FAILED ||
                    r.state == AgentState.REJECTED || r.state == AgentState.WAITING_USER,
                "非预期状态: $text -> $r"
            )
            // 不变量：L1 需要模型时必须已调用（llmInvoked）；空候选必须带 reasonCode。
            val snap = holder.snapshot
            assertNotNull(snap)
            if (snap!!.finalTier == IntentTier.L1_LOCAL_TOOL_REASONING) {
                if (snap.llmInvoked == false) {
                    assertNotNull(snap.reasonCode, "L1 未调用 LLM 必须有 reasonCode: $text")
                    assertNotNull(snap.terminalStage, "L1 未调用 LLM 必须有终止阶段: $text")
                }
            }
        }
    }
}
