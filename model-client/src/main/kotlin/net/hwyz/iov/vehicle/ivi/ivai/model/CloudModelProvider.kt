package net.hwyz.iov.vehicle.ivi.ivai.model

/**
 * Reserved cloud model provider (IVI-IVAI-DSN-CR-001: CloudModelProvider 预留).
 * Will be wired to handle CLOUD_AI route in a later CR.
 */
class CloudModelProvider : ModelProvider {
    override suspend fun generate(request: ModelRequest): ModelResponse {
        throw UnsupportedOperationException(
            "IVAI v0.1: CloudModelProvider is a reserved provider, not implemented yet"
        )
    }
}
