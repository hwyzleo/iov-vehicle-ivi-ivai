package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.adapter.mock.MockClimateToolAdapter
import net.hwyz.iov.vehicle.ivi.ivai.agent.policy.AgentPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.FailureStrategy
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowPolicy
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowStep
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.AdapterRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.DefaultToolExecutor
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionContext
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolPolicyEngine
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolValidator
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.VehicleStateProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-008 验证设计 · WorkflowRuntime 集成测试：
 *  露营模式（power_on + set_temp）执行成功、确认门禁、补偿、SKIP 部分成功、
 *  校验拒绝（IVAI-WORKFLOW-001）。
 */
class WorkflowRuntimeTest {

    private fun buildRuntime(adapter: MockClimateToolAdapter): Triple<WorkflowRuntime, MockClimateToolAdapter, ToolRegistry> {
        val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
        val validator = ToolValidator(registry)
        val agentPolicy = AgentPolicyEngine(registry, ToolPolicyEngine())
        val executor = DefaultToolExecutor(
            registry = registry,
            adapterRegistry = AdapterRegistry().register(adapter)
        )
        val runtime = WorkflowRuntime(
            registry = registry,
            toolValidator = validator,
            agentPolicy = agentPolicy,
            toolExecutor = executor,
            workflowValidator = WorkflowValidator(registry),
            vehicleStateProvider = VehicleStateProvider { adapter.snapshot() }
        )
        return Triple(runtime, adapter, registry)
    }

    private fun context(requestId: String = "req-wf") =
        ExecutionContext(requestId = requestId, sessionId = "s", source = "mock")

    @Test
    fun `露营模式多步骤执行成功`() = runTest {
        val adapter = MockClimateToolAdapter()
        val (runtime, _, _) = buildRuntime(adapter)
        val result = runtime.execute(WorkflowRegistry.CAMPING_MODE, emptyMap(), context(), approval = { true })

        assertEquals(WorkflowExecutionState.SUCCEEDED, result.state)
        assertNull(result.errorCode)
        assertEquals(2, result.stepResults.size)
        assertTrue(adapter.state.powerOn)
        assertEquals(26.0, adapter.state.driverTemperature)
        assertEquals("s2.set_temp", result.stepResults[1].stepId)
    }

    @Test
    fun `确认门禁拒绝步骤则 Workflow 终止`() = runTest {
        val adapter = MockClimateToolAdapter()
        val (runtime, _, _) = buildRuntime(adapter)
        // 单步骤 confirm=true + ABORT：拒绝确认 → 不执行且无补偿。
        val workflow = WorkflowDefinition(
            workflowId = "wf.confirm",
            domainIds = setOf(BusinessDomainId.CABIN_COMFORT),
            name = "确认测试",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1", toolId = "climate.power_on",
                    argumentTemplate = mapOf("position" to "driver"),
                    confirm = true,
                    onFailure = FailureStrategy.ABORT
                )
            )
        )
        val result = runtime.execute(workflow, emptyMap(), context(), approval = { false })

        // 步骤 confirm=true 但未获确认 → IVAI-POLICY-001 → ABORT，且不产生副作用。
        assertEquals(WorkflowExecutionState.FAILED, result.state)
        assertEquals("IVAI-POLICY-001", result.errorCode)
        assertEquals(1, result.stepResults.size)
        assertTrue(!adapter.state.powerOn, "未获确认不得执行")
        assertNull(adapter.state.lastExecution)
    }

    @Test
    fun `步骤失败触发补偿`() = runTest {
        val adapter = MockClimateToolAdapter()
        val (runtime, _, _) = buildRuntime(adapter)
        // 第一步温度设定缺参（校验失败）→ COMPENSATE → power_off。
        val workflow = WorkflowDefinition(
            workflowId = "wf.comp",
            domainIds = setOf(BusinessDomainId.CABIN_COMFORT),
            name = "补偿测试",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1", toolId = "climate.temperature_set",
                    argumentTemplate = emptyMap(),
                    onFailure = FailureStrategy.COMPENSATE,
                    compensationToolId = "climate.power_off",
                    compensationArguments = mapOf("position" to "driver")
                )
            )
        )
        val result = runtime.execute(workflow, emptyMap(), context())

        assertEquals(WorkflowExecutionState.FAILED, result.state)
        // 根因保留：参数校验失败（IVAI-TOOL-002）；补偿成功后终止。
        assertEquals("IVAI-TOOL-002", result.errorCode)
        assertTrue(result.compensationResult != null)
        assertEquals(WorkflowExecutionState.STEP_SUCCEEDED, result.compensationResult!!.state)
        assertTrue(!adapter.state.powerOn, "补偿应关闭空调")
    }

    @Test
    fun `SKIP 失败步骤后部分成功`() = runTest {
        val adapter = MockClimateToolAdapter()
        val (runtime, _, _) = buildRuntime(adapter)
        val workflow = WorkflowDefinition(
            workflowId = "wf.skip",
            domainIds = setOf(BusinessDomainId.CABIN_COMFORT),
            name = "跳过测试",
            steps = listOf(
                WorkflowStep(
                    stepId = "s1", toolId = "climate.temperature_set",
                    argumentTemplate = emptyMap(),
                    onFailure = FailureStrategy.SKIP
                ),
                WorkflowStep(stepId = "s2", toolId = "climate.power_on", argumentTemplate = mapOf("position" to "driver"))
            )
        )
        val result = runtime.execute(workflow, emptyMap(), context())

        assertEquals(WorkflowExecutionState.PARTIALLY_SUCCEEDED, result.state)
        assertEquals(2, result.stepResults.size)
        assertTrue(adapter.state.powerOn)
    }

    @Test
    fun `校验拒绝的 Workflow 直接失败 IVAI-WORKFLOW-001`() = runTest {
        val adapter = MockClimateToolAdapter()
        val (runtime, _, _) = buildRuntime(adapter)
        val workflow = WorkflowRegistry.CAMPING_MODE.copy(
            workflowId = "wf.bad",
            steps = listOf(WorkflowStep(stepId = "s1", toolId = "not.exist"))
        )
        val result = runtime.execute(workflow, emptyMap(), context())

        assertEquals(WorkflowExecutionState.FAILED, result.state)
        assertEquals("IVAI-WORKFLOW-001", result.errorCode)
    }
}
