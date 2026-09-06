package net.hwyz.iov.vehicle.ivi.ivai.agent.testutil

import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelClientException
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelErrorKind
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelRequest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelResponse
import net.hwyz.iov.vehicle.ivi.ivai.model.StreamingModelProvider

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
                cause = e,
                rawContent = content
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

/**
 * [StubModelProvider] that also streams its content in small deltas and reports
 * a client-observed time-to-first-token (streaming enablement tests).
 */
class StreamingStubModelProvider(private val content: String) : StreamingModelProvider {

    private val delegate = StubModelProvider(content)

    override suspend fun generate(request: ModelRequest): ModelResponse = delegate.generate(request)

    override suspend fun generateStreaming(
        request: ModelRequest,
        onDelta: suspend (String) -> Unit
    ): ModelResponse {
        content.chunked(DELTA_SIZE).forEach { onDelta(it) }
        return delegate.generate(request).copy(timeToFirstTokenMs = 5L)
    }

    private companion object {
        const val DELTA_SIZE = 8
    }
}
