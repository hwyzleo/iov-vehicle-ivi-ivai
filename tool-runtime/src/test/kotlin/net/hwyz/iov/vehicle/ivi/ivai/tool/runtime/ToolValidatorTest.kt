package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolValidatorTest {

    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
    private val validator = ToolValidator(registry)

    @Test
    fun `unknown tool id reports UNKNOWN_TOOL`() {
        val issue = validator.validateToolId("climate.nonexistent")
        assertEquals(ValidationIssueKind.UNKNOWN_TOOL, issue!!.kind)
        assertEquals("climate.nonexistent", issue.toolId)
    }

    @Test
    fun `known tool id passes`() {
        assertNull(validator.validateToolId("climate.power_on"))
    }

    @Test
    fun `missing required temperature reports MISSING_ARGUMENT`() {
        val tool = registry.get("climate.temperature_set")!!
        val issues = validator.validateArguments(tool, emptyMap())
        assertTrue(issues.any { it.kind == ValidationIssueKind.MISSING_ARGUMENT && it.argument == "temperature" })
    }

    @Test
    fun `out of range temperature reports OUT_OF_RANGE`() {
        val tool = registry.get("climate.temperature_set")!!
        val issues = validator.validateArguments(tool, mapOf("temperature" to 40))
        assertTrue(issues.any { it.kind == ValidationIssueKind.OUT_OF_RANGE })
    }

    @Test
    fun `position not in enum reports NOT_IN_ENUM`() {
        val tool = registry.get("climate.power_on")!!
        val issues = validator.validateArguments(tool, mapOf("position" to "roof"))
        assertTrue(issues.any { it.kind == ValidationIssueKind.NOT_IN_ENUM })
    }

    @Test
    fun `non numeric temperature reports INVALID_ARGUMENT_TYPE`() {
        val tool = registry.get("climate.temperature_set")!!
        val issues = validator.validateArguments(tool, mapOf("temperature" to "hot"))
        assertTrue(issues.any { it.kind == ValidationIssueKind.INVALID_ARGUMENT_TYPE })
    }

    @Test
    fun `defaults are filled for step and position`() {
        val tool = registry.get("climate.temperature_increase")!!
        val normalized = validator.applyDefaultsAndNormalize(tool, emptyMap())
        assertEquals(1, normalized["step"])
        assertEquals("driver", normalized["position"])
    }

    @Test
    fun `numeric string temperature is normalized to double`() {
        val tool = registry.get("climate.temperature_set")!!
        val normalized = validator.applyDefaultsAndNormalize(tool, mapOf("temperature" to "24"))
        assertEquals(24.0, normalized["temperature"])
    }

    @Test
    fun `unit suffix is stripped during normalization`() {
        val tool = registry.get("climate.temperature_set")!!
        val normalized = validator.applyDefaultsAndNormalize(tool, mapOf("temperature" to "24℃"))
        assertEquals(24.0, normalized["temperature"])
    }

    @Test
    fun `provided values are preserved over defaults`() {
        val tool = registry.get("climate.temperature_increase")!!
        val normalized = validator.applyDefaultsAndNormalize(tool, mapOf("step" to 3))
        assertEquals(3, normalized["step"])
    }
}
