package net.hwyz.iov.vehicle.ivi.ivai.model

/**
 * Unified model access contract. All model providers (Ollama / Cloud / Android local)
 * expose the same interface so agent-core stays provider-agnostic.
 */
interface ModelProvider {
    suspend fun generate(request: ModelRequest): ModelResponse
}
