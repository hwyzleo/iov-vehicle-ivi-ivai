package net.hwyz.iov.vehicle.ivi.ivai.adapter.mock

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionContext
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MockClimateToolAdapterTest {

    private val state = MockVehicleState()
    private val adapter = MockClimateToolAdapter(state)

    private fun context() = ExecutionContext(requestId = "req-a", sessionId = "s-a")

    @Test
    fun `power on sets power and reports change`() = runTest {
        val result = adapter.execute("powerOn", emptyMap(), context())
        assertEquals(ExecutionStatus.SUCCEEDED, result.status)
        assertTrue(state.powerOn)
        assertEquals(true, result.stateChanges["powerOn"])
        assertEquals("powerOn", state.lastExecution)
    }

    @Test
    fun `power off clears power`() = runTest {
        state.powerOn = true
        adapter.execute("powerOff", emptyMap(), context())
        assertFalse(state.powerOn)
    }

    @Test
    fun `increase temperature bumps driver by default step of one`() = runTest {
        state.driverTemperature = 24.0
        val result = adapter.execute("increaseTemperature", emptyMap(), context())
        assertEquals(25.0, state.driverTemperature)
        assertEquals(25.0, result.stateChanges["driver"])
    }

    @Test
    fun `decrease temperature with explicit step`() = runTest {
        state.driverTemperature = 24.0
        adapter.execute("decreaseTemperature", mapOf("step" to 2), context())
        assertEquals(22.0, state.driverTemperature)
    }

    @Test
    fun `set temperature applies to driver by default`() = runTest {
        adapter.execute("setTemperature", mapOf("temperature" to 26.0), context())
        assertEquals(26.0, state.driverTemperature)
    }

    @Test
    fun `set temperature applies to passenger zone`() = runTest {
        adapter.execute("setTemperature", mapOf("position" to "passenger", "temperature" to 25.0), context())
        assertEquals(25.0, state.passengerTemperature)
        assertEquals(24.0, state.driverTemperature)
    }

    @Test
    fun `temperature changes are clamped within 16 to 32`() = runTest {
        state.driverTemperature = 31.5
        adapter.execute("increaseTemperature", mapOf("step" to 5), context())
        assertEquals(32.0, state.driverTemperature)
    }

    @Test
    fun `query status returns full snapshot`() = runTest {
        state.powerOn = true
        state.driverTemperature = 25.0
        val result = adapter.execute("queryStatus", emptyMap(), context())
        assertEquals(ExecutionStatus.SUCCEEDED, result.status)
        assertEquals(true, result.stateChanges["powerOn"])
        assertEquals(25.0, result.stateChanges["driverTemperature"])
    }

    @Test
    fun `unknown method returns IVAI-EXEC-001 failure`() = runTest {
        val result = adapter.execute("unsupportedThing", emptyMap(), context())
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals("IVAI-EXEC-001", result.errorCode)
    }

    @Test
    fun `snapshot exposes power state`() {
        state.powerOn = true
        assertTrue(adapter.snapshot().powerOn)
    }
}
