package net.hwyz.iov.vehicle.ivi.ivai.agent.testutil

import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelClientException
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelErrorKind
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse

/**
 * Deterministic model provider for workflow unit tests. Consumes queued content strings;
 * throws when a content string is not valid JSON (matching OllamaModelProvider contract).
 */
class StubModelProvider(
    vararg contents: String
) : ModelProvider {

    private val queue = ArrayDeque(contents.toList())

    val requests: Int get() = requestCount
    private var requestCount = 0

    override suspend fun generate(request: ModelRequest): ModelResponse {
        requestCount++
        val content = queue.removeFirst()
        val contentJson = try {
            Json.parseToJsonElement(content)
        } catch (e: Exception) {
            throw ModelClientException(
                kind = ModelErrorKind.RESPONSE_PARSE_ERROR,
                message = "stub: content not valid JSON: ${e.message}",
                cause = e
            )
        }
        return ModelResponse(
            requestId = request.requestId,
            content = content,
            contentJson = contentJson,
            model = "qwen3.5:4b",
            finishReason = "stop",
            latencyMs = 5
        )
    }

    fun queue(vararg contents: String) {
        contents.forEach { queue.addLast(it) }
    }
}
