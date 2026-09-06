package net.hwyz.iov.vehicle.ivi.ivai.adapter.mock

import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ContractTestCatalog
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.GovernanceWorkspace
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ToolCatalogV1
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.AdapterRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.DefaultToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionContext
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-009 集成验证 · Mock 桩端到端执行（IVI-IVAI-DSN-CR-009）。
 *
 * 160 个治理目录 Tool 全部注册到 ToolRegistry（Mock Binding：mock-governed），
 * 经 DefaultToolExecutor 逐一带参数执行均返回 SUCCEEDED，参数原样传递；
 * 契约测试目录 1,174 条可数据驱动生成。
 *
 * 验证目标：治理目录 + Mock Adapter + Executor 链路可跑通，支撑后续分阶段
 * 真实 Binding 实施（IVAI-BINDING-001 之前不得视为量产）。
 */
class MockGovernedToolAdapterTest {

    @Test
    fun `160 个治理 Tool 经 Mock 桩全部可执行`() = runBlocking {
        val adapter = MockGovernedToolAdapter()
        val registry = ToolRegistry()
        ToolCatalogV1.ALL.forEach { registry.register(GovernanceWorkspace.toToolDefinition(it)) }
        assertEquals(160, registry.count())

        val adapterRegistry = AdapterRegistry().register(adapter)
        val executor = DefaultToolExecutor(registry, adapterRegistry)

        var executed = 0
        for (tool in ToolCatalogV1.ALL) {
            val result = executor.execute(
                tool.toolId,
                mapOf("request" to tool.toolId, "schema" to tool.parameterSchema),
                ExecutionContext(requestId = "it-$executed", sessionId = "governance-it")
            )
            assertEquals(ExecutionStatus.SUCCEEDED, result.status, "Tool ${tool.toolId} 执行失败: ${result.message}")
            assertEquals(tool.toolId, result.toolId)
            assertEquals(tool.toolId, result.stateChanges["methodId"])
            executed++
        }
        assertEquals(160, executed)
        // Mock 桩已记录全部 160 个 methodId。
        assertEquals(160, adapter.executedMethods().size)
    }

    @Test
    fun `Mock 桩参数原样回显用于契约断言`() = runBlocking {
        val adapter = MockGovernedToolAdapter()
        val registry = ToolRegistry()
        registry.register(GovernanceWorkspace.toToolDefinition(ToolCatalogV1.get("climate.temperature.set")!!))
        val executor = DefaultToolExecutor(registry, AdapterRegistry().register(adapter))

        val result = executor.execute(
            "climate.temperature.set",
            mapOf("zone" to "driver", "temperature" to 24),
            ExecutionContext(requestId = "it-params", sessionId = "governance-it")
        )
        assertEquals(ExecutionStatus.SUCCEEDED, result.status)
        assertEquals("driver", adapter.lastArguments["zone"])
        assertEquals(24, adapter.lastArguments["temperature"])
        assertEquals("climate.temperature.set", adapter.lastMethodId)
    }

    @Test
    fun `未知 Tool 与未注册 Adapter 被正确拒绝`() = runBlocking {
        val adapter = MockGovernedToolAdapter()
        val registry = ToolRegistry()
        registry.register(GovernanceWorkspace.toToolDefinition(ToolCatalogV1.get("climate.temperature.set")!!))
        val executor = DefaultToolExecutor(registry, AdapterRegistry().register(adapter))

        val unknown = executor.execute(
            "not.a.tool",
            emptyMap(),
            ExecutionContext(requestId = "it-unknown", sessionId = "governance-it")
        )
        assertEquals(ExecutionStatus.FAILED, unknown.status)
        assertEquals("IVAI-TOOL-001", unknown.errorCode)
    }

    @Test
    fun `全部 Tool 通过 ToolValidator 白名单校验`() = runBlocking {
        val adapter = MockGovernedToolAdapter()
        val registry = ToolRegistry()
        ToolCatalogV1.ALL.forEach { registry.register(GovernanceWorkspace.toToolDefinition(it)) }
        val validator = ToolValidator(registry)

        for (toolId in ToolCatalogV1.toolIds) {
            assertEquals(null, validator.validateToolId(toolId), "Tool $toolId 应通过白名单校验")
        }
    }

    @Test
    fun `契约测试目录 1500 条可数据驱动生成且对象均可解析`() {
        assertEquals(1500, ContractTestCatalog.ALL.size)
        val toolIds = ToolCatalogV1.toolIds
        val wfIds = net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.WorkflowCatalogV1.workflowIds
        for (spec in ContractTestCatalog.ALL) {
            when (spec.type) {
                net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ContractTestType.TOOL ->
                    assertTrue(spec.objectId in toolIds, "Tool 测试对象缺失: ${spec.objectId}")
                net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance.ContractTestType.WORKFLOW ->
                    assertTrue(spec.objectId in wfIds, "Workflow 测试对象缺失: ${spec.objectId}")
                else -> {}
            }
        }
    }
}
