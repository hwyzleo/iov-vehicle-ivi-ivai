package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentOutput
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentRoute
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RouterTest {

    private val router = Router()

    @Test
    fun `resolves the four supported routes`() {
        assertEquals(
            AgentRoute.LOCAL_TOOL,
            (router.resolve(AgentOutput(route = "LOCAL_TOOL", intents = listOf(intent("climate.power_on")))) as RouteDecision.Safe).route
        )
        assertEquals(
            AgentRoute.LOCAL_DIALOGUE,
            (router.resolve(AgentOutput(route = "LOCAL_DIALOGUE")) as RouteDecision.Safe).route
        )
        assertEquals(
            AgentRoute.CLOUD_AI,
            (router.resolve(AgentOutput(route = "CLOUD_AI")) as RouteDecision.Safe).route
        )
        assertEquals(
            AgentRoute.REJECT,
            (router.resolve(AgentOutput(route = "REJECT")) as RouteDecision.Safe).route
        )
    }

    @Test
    fun `unknown route value is unsafe`() {
        val decision = router.resolve(AgentOutput(route = "FLY_TO_MOON"))
        assertTrue(decision is RouteDecision.Unsafe)
    }

    @Test
    fun `LOCAL_TOOL without intents is unsafe`() {
        val decision = router.resolve(AgentOutput(route = "LOCAL_TOOL", intents = emptyList()))
        assertTrue(decision is RouteDecision.Unsafe)
    }

    @Test
    fun `LOCAL_TOOL with multiple intents is unsafe in v01`() {
        val decision = router.resolve(
            AgentOutput(route = "LOCAL_TOOL", intents = listOf(intent("a"), intent("b")))
        )
        assertTrue(decision is RouteDecision.Unsafe)
    }

    private fun intent(toolId: String) = Intent(toolId = toolId)
}
