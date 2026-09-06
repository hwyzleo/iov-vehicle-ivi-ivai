package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.GovernanceStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.FailureStrategy
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowPolicy
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowStep
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.workflows.WorkflowRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-008 验证设计 · WorkflowValidator 测试：
 *  未批准/未启用（IVAI-GOV-001）、步骤数超限（IVAI-WORKFLOW-002）、
 *  未知 Tool / 缺补偿（IVAI-WORKFLOW-001）、跨领域限制。
 */
class WorkflowValidatorTest {

    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
    private val validator = WorkflowValidator(registry)

    @Test
    fun `露营模式通过校验`() {
        val result = validator.validate(WorkflowRegistry.CAMPING_MODE)
        assertTrue(result is WorkflowValidationResult.Valid)
    }

    @Test
    fun `未批准治理状态的 Workflow 被拒绝 IVAI-GOV-001`() {
        val draft = WorkflowRegistry.CAMPING_MODE.copy(status = GovernanceStatus.DRAFT)
        val result = validator.validate(draft)
        assertTrue(result is WorkflowValidationResult.Invalid)
        assertEquals("IVAI-GOV-001", (result as WorkflowValidationResult.Invalid).errorCode)
    }

    @Test
    fun `步骤数超过策略限制被拒绝 IVAI-WORKFLOW-002`() {
        val steps = (1..5).map { i ->
            WorkflowStep(stepId = "s$i", toolId = "climate.power_on")
        }
        val workflow = WorkflowDefinition(
            workflowId = "wf.too_many",
            domainIds = setOf(BusinessDomainId.CABIN_COMFORT),
            name = "超限",
            steps = steps,
            policy = WorkflowPolicy(maxSteps = 3)
        )
        val result = validator.validate(workflow)
        assertTrue(result is WorkflowValidationResult.Invalid)
        assertEquals("IVAI-WORKFLOW-002", (result as WorkflowValidationResult.Invalid).errorCode)
    }

    @Test
    fun `步骤引用未知 Tool 被拒绝 IVAI-WORKFLOW-001`() {
        val workflow = WorkflowRegistry.CAMPING_MODE.copy(
            workflowId = "wf.unknown",
            steps = listOf(
                WorkflowStep(stepId = "s1", toolId = "not.exist.tool")
            )
        )
        val result = validator.validate(workflow)
        assertTrue(result is WorkflowValidationResult.Invalid)
        assertEquals("IVAI-WORKFLOW-001", (result as WorkflowValidationResult.Invalid).errorCode)
    }

    @Test
    fun `COMPENSATE 策略缺少补偿 Tool 被拒绝`() {
        val workflow = WorkflowRegistry.CAMPING_MODE.copy(
            workflowId = "wf.no_comp",
            steps = listOf(
                WorkflowStep(stepId = "s1", toolId = "climate.temperature_set", onFailure = FailureStrategy.COMPENSATE)
            )
        )
        val result = validator.validate(workflow)
        assertTrue(result is WorkflowValidationResult.Invalid)
        assertEquals("IVAI-WORKFLOW-001", (result as WorkflowValidationResult.Invalid).errorCode)
    }

    @Test
    fun `空步骤 Workflow 被拒绝`() {
        val workflow = WorkflowRegistry.CAMPING_MODE.copy(workflowId = "wf.empty", steps = emptyList())
        val result = validator.validate(workflow)
        assertTrue(result is WorkflowValidationResult.Invalid)
        assertEquals("IVAI-WORKFLOW-001", (result as WorkflowValidationResult.Invalid).errorCode)
    }
}
