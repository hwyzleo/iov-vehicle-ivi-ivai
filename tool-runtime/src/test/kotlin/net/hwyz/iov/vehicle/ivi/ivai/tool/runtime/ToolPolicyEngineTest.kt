package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.RiskLevel
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolExecutionBinding
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolPolicyEngineTest {

    private val engine = ToolPolicyEngine()
    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())

    @Test
    fun `low risk tool is authorized when no precondition`() {
        val tool = registry.get("climate.power_on")!!
        val decision = engine.check(tool, emptyMap(), VehicleStateSnapshot(powerOn = false))
        assertTrue(decision.authorized)
        assertEquals(PolicyReason.OK, decision.reason)
    }

    @Test
    fun `temperature tool is authorized regardless of power in v01`() {
        val tool = registry.get("climate.temperature_increase")!!
        val decision = engine.check(tool, emptyMap(), VehicleStateSnapshot(powerOn = false))
        assertTrue(decision.authorized)
    }

    @Test
    fun `high risk tool requires confirmation`() {
        val tool = synthetic(
            id = "demo.high_risk",
            policy = ToolPolicy(riskLevel = RiskLevel.HIGH)
        )
        val decision = engine.check(tool, emptyMap(), VehicleStateSnapshot(powerOn = true))
        assertFalse(decision.authorized)
        assertEquals(PolicyReason.RISK_REQUIRES_CONFIRMATION, decision.reason)
    }

    @Test
    fun `requiresConfirmation tool requires confirmation`() {
        val tool = synthetic(
            id = "demo.confirm",
            policy = ToolPolicy(riskLevel = RiskLevel.LOW, requiresConfirmation = true)
        )
        val decision = engine.check(tool, emptyMap(), VehicleStateSnapshot(powerOn = true))
        assertFalse(decision.authorized)
        assertEquals(PolicyReason.RISK_REQUIRES_CONFIRMATION, decision.reason)
    }

    @Test
    fun `power-on precondition blocks execution when off`() {
        val tool = synthetic(
            id = "demo.needs_power",
            policy = ToolPolicy(riskLevel = RiskLevel.LOW, requiresPowerOn = true)
        )
        val decision = engine.check(tool, emptyMap(), VehicleStateSnapshot(powerOn = false))
        assertFalse(decision.authorized)
        assertEquals(PolicyReason.PRECONDITION_UNMET, decision.reason)
    }

    @Test
    fun `power-on precondition passes when on`() {
        val tool = synthetic(
            id = "demo.needs_power",
            policy = ToolPolicy(riskLevel = RiskLevel.LOW, requiresPowerOn = true)
        )
        val decision = engine.check(tool, emptyMap(), VehicleStateSnapshot(powerOn = true))
        assertTrue(decision.authorized)
    }

    private fun synthetic(id: String, policy: ToolPolicy) = ToolDefinition(
        toolId = id,
        functionId = null,
        name = id,
        description = "synthetic",
        positiveExamples = emptyList(),
        negativeExamples = emptyList(),
        selectionPriority = 1,
        parameterSchema = """{"type":"object","properties":{},"required":[]}""",
        policy = policy,
        execution = ToolExecutionBinding(adapterId = "mock-vehicle", methodId = "noop")
    )
}
