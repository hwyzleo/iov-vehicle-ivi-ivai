package net.hwyz.iov.vehicle.ivi.ivai.model.provider.openai

import kotlinx.serialization.Serializable

/**
 * Standard OpenAI error envelope: `{"error": {"message": "...", "type": "...", "code": ...}}`.
 * Used for logging and for mapping auth / rate-limit failures to unified errors.
 */
@Serializable
data class OpenAiErrorResponse(
    val error: OpenAiErrorDetail? = null
)

@Serializable
data class OpenAiErrorDetail(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)
