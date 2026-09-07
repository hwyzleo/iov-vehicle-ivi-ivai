package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.agent.AgentState
import net.hwyz.iov.vehicle.ivi.ivai.agent.session.Session
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.CollectingAgentEventListener
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.StubModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.agent.testutil.TestGraph
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.DeterministicIntentCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ToolAliasCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ToolCatalogV1
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.SchemaParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-009 集成验证 · 160 个治理 Tool 运行时全链路（Mock 桩）。
 *
 * 与 AgentService.buildAgentGraph 的 CR-009 装配一致：全部 160 个治理 Tool 注册进
 * 运行时，18 个 Capability Pack 桩启用，mock-governed 适配器支撑执行。验证：
 *  - 160 个 Tool 全部注册且通过白名单；
 *  - 每个 Tool 经模型返回 → 领域/能力包路由 → 校验 → Policy → 执行（LOW/MEDIUM）
 *    或确认（HIGH）链路可跑通，不因未注册而拒于路由前。
 */
class GovernanceRuntimeChainTest {

    private fun input(requestId: String, text: String, turnId: String = requestId) =
        AgentInput(requestId = requestId, text = text, source = "mock", turnId = turnId)

    @Test
    fun `全部 160 个治理 Tool 注册且通过白名单校验`() {
        val (_, _, registry) = TestGraph.buildGovernedStubGraph(StubModelProvider())
        assertEquals(160, registry.count(), "160 个治理 Tool 应全部注册")
        val validator = net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator(registry)
        for (toolId in ToolCatalogV1.toolIds) {
            assertEquals(null, validator.validateToolId(toolId), "Tool $toolId 应通过白名单")
        }
        // 领域覆盖 BD01～BD10 全部存在。
        assertEquals(
            ToolCatalogV1.ALL.map { it.domainId }.toSet().size,
            10
        )
    }

    @Test
    fun `LOW 与 MEDIUM 风险 Tool 经模型返回后由 Mock 桩成功执行`() = runTest {
        val l0Catalog = DeterministicIntentCatalog.build()
        val lowAndMedium = ToolCatalogV1.ALL.filter {
            !it.policySummary.startsWith("HIGH") &&
                !containsSafetyKeyword(it.name) &&
                // CR-010/CR-013：具备生产启用 L0 Profile 的 Tool（SUPPORTED + 规则）由
                // 请求级确定性匹配走 L0（不调用模型），由 L0 契约测试覆盖；本测试验证
                // L1 模型路径，排除 L0 项可避免模型响应队列漂移（L0 不消费队列）。
                !l0Catalog.profileFor(it.toolId)?.productionEnabled!!
        }
        assertTrue(lowAndMedium.size > 0, "应存在 LOW/MEDIUM 风险 Tool")

        val listener = CollectingAgentEventListener()
        val stub = StubModelProvider()
        val (workflow, adapter, registry) = TestGraph.buildGovernedStubGraph(stub, eventListener = listener)

        var executed = 0
        lowAndMedium.forEachIndexed { index, tool ->
            val args = minimalArguments(tool.toolId, registry)
            stub.queue(
                """{"route":"LOCAL_TOOL","intents":[{"toolId":"${tool.toolId}","arguments":{${argsJson(args)}}}],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"EXPLICIT_INTENT"}"""
            )
            val result = workflow.process(input("req-$index", tool.name, turnId = "turn-$index"), Session())
            assertEquals(AgentState.SUCCEEDED, result.state, "Tool ${tool.toolId} 应执行成功，实际 ${result.state}（${result.errorCode}）")
            assertEquals(ExecutionStatus.SUCCEEDED, result.executionResult?.status, "Tool ${tool.toolId} 执行结果")
            executed++
        }
        assertEquals(lowAndMedium.size, executed)
        assertEquals(executed, adapter.executedMethods().size, "Mock 桩应记录全部执行")
    }

    @Test
    fun `名称含驾驶安全关键词的 Tool 被安全预检拒绝`() = runTest {
        // 既有设计（CR-005 安全预检）：名称含「方向盘/转向/车速」等关键词 → REJECT_SAFETY。
        val safetyHit = ToolCatalogV1.ALL.filter { containsSafetyKeyword(it.name) }
        assertTrue(safetyHit.isNotEmpty(), "应存在名称含安全关键词的 Tool")
        val stub = StubModelProvider()
        val (workflow, adapter, _) = TestGraph.buildGovernedStubGraph(stub)
        val tool = safetyHit.first()
        stub.queue(
            """{"route":"LOCAL_TOOL","intents":[{"toolId":"${tool.toolId}","arguments":{}}],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"EXPLICIT_INTENT"}"""
        )
        val result = workflow.process(input("req-safety", tool.name, turnId = "turn-safety"), Session())
        assertEquals(AgentState.REJECTED, result.state, "${tool.name} 应被安全预检拒绝")
        assertTrue(adapter.executedMethods().isEmpty(), "安全预检后不得执行")
    }

    private fun containsSafetyKeyword(text: String): Boolean =
        listOf(
            "开车", "驾驶", "刹车", "制动", "转向", "方向盘", "油门", "加速",
            "挂挡", "开走", "变道", "倒车", "漂移", "自动驾驶", "车速"
        ).any { text.contains(it) }

    /**
     * 按展开后的运行时 Schema 生成最小合法参数（每个必填参数取枚举首值 / 类型默认），
     * 使桩模型输出能通过 Schema 校验——校验语义由治理 Schema 驱动（CR-009）。
     */
    private fun minimalArguments(toolId: String, registry: ToolRegistry): Map<String, Any?> {
        val schema = SchemaParser.parse(registry.get(toolId)!!.parameterSchema)
        return buildMap {
            for (key in schema.required) {
                val prop = schema.properties[key]
                put(
                    key,
                    when (prop?.type) {
                        "boolean" -> true
                        "integer" -> (prop.minimum ?: 0.0).toInt()
                        "number" -> prop.minimum ?: 0.0
                        "string" -> prop.enum?.firstOrNull() ?: key
                        "array" -> emptyList<Any?>()
                        "object" -> emptyMap<String, Any?>()
                        else -> key
                    }
                )
            }
        }
    }

    private fun argsJson(args: Map<String, Any?>): String = args.entries.joinToString(",") { (k, v) ->
        "\"$k\": " + when (v) {
            is Boolean -> v.toString()
            is Number -> v.toString()
            else -> "\"$v\""
        }
    }

    @Test
    fun `HIGH 风险 Tool 触发确认流程而非直接执行`() = runTest {
        val high = ToolCatalogV1.ALL.filter { it.policySummary.startsWith("HIGH") }
        assertTrue(high.isNotEmpty(), "应存在 HIGH 风险 Tool")

        val stub = StubModelProvider()
        val (workflow, adapter, registry) = TestGraph.buildGovernedStubGraph(stub)
        val tool = high.first()
        val args = minimalArguments(tool.toolId, registry)
        stub.queue(
            """{"route":"LOCAL_TOOL","intents":[{"toolId":"${tool.toolId}","arguments":{${argsJson(args)}}}],"modelConfidence":0.9,"riskLevel":"high","needConfirmation":false,"missingArguments":[],"reasonCode":"EXPLICIT_INTENT"}"""
        )
        val result = workflow.process(input("req-high", tool.name, turnId = "turn-high"), Session())

        // HIGH 风险 → 需要确认，等待用户；不得直接执行。
        assertEquals(AgentState.WAITING_USER, result.state, "HIGH 风险 Tool 应进入确认")
        assertTrue(adapter.executedMethods().isEmpty(), "确认前不得执行")
    }

    @Test
    fun `除霜等未在旧 6 工具集中的 Tool 也能经治理目录执行`() = runTest {
        val listener = CollectingAgentEventListener()
        val stub = StubModelProvider()
        val (workflow, adapter, _) = TestGraph.buildGovernedStubGraph(stub, eventListener = listener)
        stub.queue(
            """{"route":"LOCAL_TOOL","intents":[{"toolId":"climate.defrost.set","arguments":{"target":"front","enabled":true}}],"modelConfidence":0.9,"riskLevel":"low","needConfirmation":false,"missingArguments":[],"reasonCode":"EXPLICIT_INTENT"}"""
        )
        val result = workflow.process(input("req-defrost", "空调设置成除霜", turnId = "turn-defrost"), Session())

        assertEquals(AgentState.SUCCEEDED, result.state, "除霜 Tool 应能经治理目录执行：${result.errorCode}")
        assertEquals("climate.defrost.set", adapter.lastMethodId)
        assertEquals(true, adapter.lastArguments["enabled"])
        assertEquals("front", adapter.lastArguments["target"])
    }
}
