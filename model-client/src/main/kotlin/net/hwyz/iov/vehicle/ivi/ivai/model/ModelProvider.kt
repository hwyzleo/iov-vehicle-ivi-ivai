package net.hwyz.iov.vehicle.ivi.ivai.model

/**
 * Supported model provider backends (IVI-IVAI-DSN-CR-004). The type is part of
 * the persisted public config (schema v2) and drives the provider factory.
 */
enum class ModelProviderType {
    /** Local / remote Ollama Chat API. */
    OLLAMA,

    /** OpenAI Chat Completions compatible API (baseUrl + optional endpoint path). */
    OPENAI_COMPATIBLE;

    companion object {
        fun fromName(name: String?): ModelProviderType? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * Unified model access contract. All model providers (Ollama / OpenAI
 * compatible / Cloud / Android local) expose the same interface so agent-core
 * stays provider-agnostic (IVI-IVAI-DSN-CR-001 / CR-004).
 */
interface ModelProvider {
    suspend fun generate(request: ModelRequest): ModelResponse
}

/**
 * Optional capability for providers that can stream token deltas (streaming
 * enablement beyond the initial non-streaming baseline). Invokes [onDelta] as
 * content chunks arrive while still returning the fully accumulated
 * [ModelResponse], enabling real time-to-first-token measurement and
 * token-by-token rendering. The structured JSON output is streamed raw into the
 * processing bubble and later finalized into the user-facing result.
 */
interface StreamingModelProvider : ModelProvider {
    suspend fun generateStreaming(
        request: ModelRequest,
        onDelta: suspend (String) -> Unit
    ): ModelResponse
}
