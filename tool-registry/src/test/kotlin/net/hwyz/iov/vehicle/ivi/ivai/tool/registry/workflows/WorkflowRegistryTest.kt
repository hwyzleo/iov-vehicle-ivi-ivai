package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-008 验证设计 · Workflow 模型测试：
 *  步骤引用已注册 Tool、参数模板、失败/补偿策略、步骤数限制与治理状态。
 */
class WorkflowRegistryTest {

    @Test
    fun `露营模式注册为空调域 Workflow 且步骤引用已绑定 Tool`() {
        val workflow = WorkflowRegistry.CAMPING_MODE
        assertEquals("cabin.camping_mode", workflow.workflowId)
        assertTrue(workflow.domainIds.contains(BusinessDomainId.CABIN_COMFORT))
        assertTrue(workflow.available)
        assertEquals(GovernanceStatus.APPROVED, workflow.status)

        val stepIds = workflow.steps.map { it.stepId }
        assertEquals(listOf("s1.power_on", "s2.set_temp"), stepIds)
        assertEquals("climate.power_on", workflow.steps[0].toolId)
        assertEquals("climate.temperature_set", workflow.steps[1].toolId)
        assertEquals(26, workflow.steps[1].argumentTemplate["temperature"])
    }

    @Test
    fun `失败补偿与确认策略被保留`() {
        val step2 = WorkflowRegistry.CAMPING_MODE.steps[1]
        assertEquals(FailureStrategy.COMPENSATE, step2.onFailure)
        assertEquals("climate.power_off", step2.compensationToolId)
        assertTrue(step2.confirm, "涉及设定温度步骤应要求确认")
    }

    @Test
    fun `步骤数与跨域限制在策略层生效`() {
        val policy = WorkflowRegistry.CAMPING_MODE.policy
        assertEquals(2, policy.maxSteps)
        assertEquals(false, policy.allowedCrossDomain)
        assertEquals(WorkflowPolicy.DEFAULT_MAX_STEPS, WorkflowPolicy().maxSteps)
        // 定义级 maxSteps 默认取 WorkflowPolicy.DEFAULT_MAX_STEPS，实际执行取 min(定义, 策略)；
        // 露营模式策略把上限收紧到 2，步骤数 2 不超限。
        assertEquals(2, policy.maxSteps)
        assertTrue(WorkflowRegistry.CAMPING_MODE.steps.size <= policy.maxSteps)
    }

    @Test
    fun `注册目录可按 id 查询且可用集合只含已批准`() {
        assertEquals(WorkflowRegistry.CAMPING_MODE, WorkflowRegistry.get("cabin.camping_mode"))
        assertEquals(null, WorkflowRegistry.get("not.exist"))
        assertEquals(WorkflowRegistry.ALL.filter { it.available }, WorkflowRegistry.AVAILABLE)
    }
}
