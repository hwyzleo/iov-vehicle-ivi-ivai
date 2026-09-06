package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-008 验证设计 · Workflow 状态机测试：合法迁移与非法迁移。
 */
class WorkflowStateMachineTest {

    @Test
    fun `主流程迁移合法`() {
        val machine = WorkflowStateMachine()
        assertEquals(WorkflowExecutionState.CREATED, machine.state)
        assertTrue(machine.transition(WorkflowExecutionState.VALIDATED))
        assertTrue(machine.transition(WorkflowExecutionState.AUTHORIZED))
        assertTrue(machine.transition(WorkflowExecutionState.RUNNING))
        assertTrue(machine.transition(WorkflowExecutionState.STEP_RUNNING))
        assertTrue(machine.transition(WorkflowExecutionState.STEP_SUCCEEDED))
        assertTrue(machine.transition(WorkflowExecutionState.STEP_RUNNING))
        assertTrue(machine.transition(WorkflowExecutionState.STEP_SUCCEEDED))
        assertTrue(machine.transition(WorkflowExecutionState.SUCCEEDED))
        assertEquals(WorkflowExecutionState.SUCCEEDED, machine.state)
    }

    @Test
    fun `非法迁移被拒绝且状态不变`() {
        val machine = WorkflowStateMachine()
        // CREATED → SUCCEEDED 非法。
        assertFalse(machine.transition(WorkflowExecutionState.SUCCEEDED))
        assertEquals(WorkflowExecutionState.CREATED, machine.state)
        // 终态不可再迁移。
        machine.transition(WorkflowExecutionState.FAILED)
        assertFalse(machine.transition(WorkflowExecutionState.RUNNING))
    }

    @Test
    fun `失败后可补偿或取消`() {
        val machine = WorkflowStateMachine()
        machine.transition(WorkflowExecutionState.VALIDATED)
        machine.transition(WorkflowExecutionState.AUTHORIZED)
        machine.transition(WorkflowExecutionState.RUNNING)
        machine.transition(WorkflowExecutionState.STEP_RUNNING)
        machine.transition(WorkflowExecutionState.STEP_FAILED)
        assertTrue(machine.canTransition(WorkflowExecutionState.COMPENSATING))
        assertTrue(machine.canTransition(WorkflowExecutionState.PARTIALLY_SUCCEEDED))
        assertTrue(machine.canTransition(WorkflowExecutionState.CANCELLED))
    }
}
