package net.hwyz.iov.vehicle.ivi.ivai.ui.settings.prompt

import net.hwyz.iov.vehicle.ivi.ivai.agent.prompt.PromptSnapshot
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService

/**
 * [PromptInfoGateway] backed by the bound [AgentService].
 */
class ServicePromptInfoGateway(private val service: AgentService) : PromptInfoGateway {
    override fun promptSnapshot(): PromptSnapshot? = service.promptSnapshot()
}
