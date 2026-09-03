package net.hwyz.iov.vehicle.ivi.ivai.agent.router

import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentOutput
import net.hwyz.iov.vehicle.ivi.ivai.agent.output.AgentRoute

sealed class RouteDecision {
    data class Safe(val route: AgentRoute) : RouteDecision()
    data class Unsafe(val message: String) : RouteDecision()
}

/**
 * Determines whether the model-proposed route can be trusted.
 * A LOCAL_TOOL route with no intents is never safe (IVAI-ROUTE-001).
 */
class Router {

    fun resolve(output: AgentOutput): RouteDecision {
        val route = AgentRoute.from(output.route)
            ?: return RouteDecision.Unsafe("Unknown route value: ${output.route}")
        if (route == AgentRoute.LOCAL_TOOL && output.intents.isEmpty()) {
            return RouteDecision.Unsafe("LOCAL_TOOL route requires at least one intent")
        }
        if (route == AgentRoute.LOCAL_TOOL && output.intents.size > 1) {
            return RouteDecision.Unsafe("Multiple intents are not executed in v0.1")
        }
        return RouteDecision.Safe(route)
    }
}
