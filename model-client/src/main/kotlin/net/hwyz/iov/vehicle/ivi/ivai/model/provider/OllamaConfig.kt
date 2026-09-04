package net.hwyz.iov.vehicle.ivi.ivai.model.provider

/**
 * Configuration for the Ollama HTTP endpoint (default: Mac sidecar on localhost).
 */
data class OllamaConfig(
    val baseUrl: String = "http://localhost:11434",
    val model: String = "qwen3.5:4b",
    val temperature: Double = 0.0,
    val maxTokens: Int = 512,
    val connectTimeoutMs: Long = 5_000,
    val readTimeoutMs: Long = 60_000
)
