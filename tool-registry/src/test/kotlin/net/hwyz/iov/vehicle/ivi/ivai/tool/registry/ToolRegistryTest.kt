package net.hwyz.iov.vehicle.ivi.ivai.tool.registry

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolRegistryTest {

    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())

    @Test
    fun `registers six climate tools`() {
        assertEquals(6, registry.count())
        assertEquals(
            setOf(
                "climate.power_on",
                "climate.power_off",
                "climate.temperature_increase",
                "climate.temperature_decrease",
                "climate.temperature_set",
                "climate.status_query"
            ),
            registry.toolIds()
        )
    }

    @Test
    fun `get returns definition and null for unknown tool`() {
        val powerOn = registry.get("climate.power_on")
        assertNotNull(powerOn)
        assertEquals("AC_Control_1", powerOn!!.functionId)
        assertEquals("mock-vehicle", powerOn.execution.adapterId)
        assertEquals("powerOn", powerOn.execution.methodId)
        assertNull(registry.get("climate.unknown"))
    }

    @Test
    fun `status_query has no function id yet`() {
        assertNull(registry.get("climate.status_query")!!.functionId)
    }

    @Test
    fun `temperature_set schema requires temperature`() {
        val schema = registry.get("climate.temperature_set")!!.parameterSchema
        assertTrue(schema.contains("\"temperature\""))
        assertTrue(schema.contains("\"required\": [\"temperature\"]"))
    }

    @Test
    fun `duplicate registration throws`() {
        val tool = registry.get("climate.power_on")!!
        assertThrows(IllegalArgumentException::class.java) {
            ToolRegistry().register(tool).register(tool)
        }
    }
}
