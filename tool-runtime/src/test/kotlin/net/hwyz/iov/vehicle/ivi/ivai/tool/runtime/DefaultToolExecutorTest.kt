package net.hwyz.iov.vehicle.ivi.ivai.tool.runtime

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.ToolRegistry
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ClimateToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultToolExecutorTest {

    private val registry = ClimateToolDefinitions.registerAll(ToolRegistry())
    private val events = mutableListOf<ToolLifecycleEvent>()
    private lateinit var executor: DefaultToolExecutor

    private fun context() = ExecutionContext(requestId = "req-x", sessionId = "s-x")

    @Test
    fun `executes power_on through the mock adapter`() = runTest {
        val adapter = FakeAdapter()
        executor = DefaultToolExecutor(registry, AdapterRegistry().register(adapter))

        val result = executor.execute("climate.power_on", emptyMap(), context())

        assertEquals(ExecutionStatus.SUCCEEDED, result.status)
        assertEquals("climate.power_on", result.toolId)
        assertTrue(adapter.calls.contains("powerOn"))
    }

    @Test
    fun `unknown tool returns IVAI-TOOL-001 failure`() = runTest {
        executor = DefaultToolExecutor(registry, AdapterRegistry())
        val result = executor.execute("climate.ghost", emptyMap(), context())
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals("IVAI-TOOL-001", result.errorCode)
    }

    @Test
    fun `missing adapter returns IVAI-EXEC-001 failure`() = runTest {
        executor = DefaultToolExecutor(registry, AdapterRegistry())
        val result = executor.execute("climate.power_on", emptyMap(), context())
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals("IVAI-EXEC-001", result.errorCode)
    }

    @Test
    fun `adapter exception returns IVAI-EXEC-001 failure`() = runTest {
        val adapter = ThrowingAdapter()
        executor = DefaultToolExecutor(registry, AdapterRegistry().register(adapter))
        val result = executor.execute("climate.power_on", emptyMap(), context())
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals("IVAI-EXEC-001", result.errorCode)
        assertTrue(result.message!!.contains("boom"))
    }

    @Test
    fun `lifecycle events cover EXECUTING SUCCEEDED REPORTED`() = runTest {
        executor = DefaultToolExecutor(
            registry,
            AdapterRegistry().register(FakeAdapter()),
            lifecycleListener = { events.add(it) }
        )
        executor.execute("climate.power_on", emptyMap(), context())
        val phases = events.map { it.phase }
        assertTrue(ToolLifecyclePhase.EXECUTING in phases)
        assertTrue(ToolLifecyclePhase.SUCCEEDED in phases)
        assertTrue(ToolLifecyclePhase.REPORTED in phases)
        assertTrue(events.all { it.requestId == "req-x" && it.toolId == "climate.power_on" })
    }

    private class FakeAdapter : VehicleToolAdapter {
        val calls = mutableListOf<String>()
        override val adapterId: String = "mock-vehicle"
        override suspend fun execute(
            methodId: String,
            arguments: Map<String, Any?>,
            context: ExecutionContext
        ): ToolExecutionResult {
            calls.add(methodId)
            return ToolExecutionResult(
                requestId = context.requestId,
                toolId = methodId,
                status = ExecutionStatus.SUCCEEDED,
                message = "ok"
            )
        }
        override fun snapshot(): VehicleStateSnapshot = VehicleStateSnapshot(true)
    }

    private class ThrowingAdapter : VehicleToolAdapter {
        override val adapterId: String = "mock-vehicle"
        override suspend fun execute(
            methodId: String,
            arguments: Map<String, Any?>,
            context: ExecutionContext
        ): ToolExecutionResult = throw RuntimeException("boom")
        override fun snapshot(): VehicleStateSnapshot = VehicleStateSnapshot(true)
    }
}
