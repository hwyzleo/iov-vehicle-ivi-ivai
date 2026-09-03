package net.hwyz.iov.vehicle.ivi.ivai.model

/**
 * Reserved on-device model provider (IVI-IVAI-DSN-CR-001: AndroidLocalProvider 预留).
 * To be implemented once target IVI hardware is available.
 */
class AndroidLocalModelProvider : ModelProvider {
    override suspend fun generate(request: ModelRequest): ModelResponse {
        throw UnsupportedOperationException(
            "IVAI v0.1: AndroidLocalModelProvider is a reserved provider, not implemented yet"
        )
    }
}
