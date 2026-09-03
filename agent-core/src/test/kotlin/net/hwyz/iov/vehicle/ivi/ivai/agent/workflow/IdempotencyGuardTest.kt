package net.hwyz.iov.vehicle.ivi.ivai.agent.workflow

import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ExecutionStatus
import net.hwyz.iov.vehicle.ivi.ivai.tool.runtime.ToolExecutionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IdempotencyGuardTest {

    private val guard = IdempotencyGuard()

    @Test
    fun `find returns null before record`() {
        assertNull(guard.find(guard.keyFor("req", "climate.power_on", emptyMap())))
    }

    @Test
    fun `record then find returns the cached result`() {
        val key = guard.keyFor("req", "climate.power_on", mapOf("position" to "driver"))
        val result = result(key)
        guard.record(key, result)
        assertEquals(result, guard.find(key))
    }

    @Test
    fun `different requestId produces a different key`() {
        val keyA = guard.keyFor("req-a", "climate.power_on", emptyMap())
        val keyB = guard.keyFor("req-b", "climate.power_on", emptyMap())
        assert(keyA != keyB)
    }

    @Test
    fun `different arguments produce a different key`() {
        val keyA = guard.keyFor("req", "climate.power_on", mapOf("position" to "driver"))
        val keyB = guard.keyFor("req", "climate.power_on", mapOf("position" to "passenger"))
        assert(keyA != keyB)
    }

    private fun result(key: String) = ToolExecutionResult(
        requestId = key,
        toolId = "climate.power_on",
        status = ExecutionStatus.SUCCEEDED,
        message = "ok"
    )
}
