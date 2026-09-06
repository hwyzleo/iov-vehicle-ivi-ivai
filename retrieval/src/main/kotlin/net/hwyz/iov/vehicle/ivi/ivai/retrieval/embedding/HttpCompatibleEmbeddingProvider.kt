package net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.RagException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * HTTP_COMPATIBLE Embedding Provider（CR-011 首期注册）。
 *
 * 在线服务只生成文档向量和查询向量。请求体采用 OpenAI 兼容格式：
 * POST {baseUrl}/embeddings  { "model": modelId, "input": [...] }。
 *
 * 安全与可靠性（CR-011 并发、性能与安全）：
 *  - 在线请求采用 HTTPS、Host 白名单、超时、限流和证书校验；
 *  - 有界重试（[EmbeddingConfig.maxRetries] + 指数退避），禁止无限阻塞；
 *  - 文档批量 Embedding 与用户查询 Embedding 使用独立限流预算（由调用方注入
 *    并发限制，本类负责单请求内分批）；
 *  - 非法维度 / NaN / Infinity 拒绝（IVAI-RAG-003），标记 Provider 配置错误。
 *
 * [credentialRef] 指向 Keystore/安全配置，由 [credentialResolver] 解析，不保存
 * 密钥明文。
 */
class HttpCompatibleEmbeddingProvider(
    private val config: net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig,
    private val credentialResolver: suspend () -> String? = { null },
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofMillis(config.timeoutMs))
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val allowInsecureHttp: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis
) : EmbeddingProvider {

    override val descriptor = EmbeddingModelDescriptor(
        providerType = config.providerType.ifBlank { "HTTP_COMPATIBLE" },
        modelId = config.modelId,
        modelVersion = config.modelVersion,
        dimension = config.dimension
    )

    override val available: Boolean =
        config.baseUrl.isNotBlank() && config.modelId.isNotBlank() && config.dimension > 0

    override suspend fun embed(request: EmbeddingRequest): EmbeddingResponse {
        if (!available) {
            throw RagException(RagErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 配置不完整（baseUrl/modelId/dimension）")
        }
        validateEndpoint()
        val apiKey = credentialResolver()
        val batch = request.batchSize.coerceIn(1, MAX_BATCH_SIZE)
        val vectors = mutableListOf<FloatArray>()
        for (chunk in request.texts.chunked(batch)) {
            vectors += embedChunkWithRetry(chunk, apiKey, attempt = 0)
        }
        validateVectors(vectors, request.texts.size)
        return EmbeddingResponse(vectors, descriptor.modelId, descriptor.dimension)
    }

    private fun validateEndpoint() {
        val uri = runCatching { URI(config.baseUrl) }.getOrNull()
            ?: throw RagException(RagErrorCode.EMBEDDING_UNAVAILABLE, "Embedding baseUrl 非法: ${config.baseUrl}")
        if (!allowInsecureHttp && uri.scheme != "https") {
            throw RagException(RagErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 必须使用 HTTPS: ${uri.scheme}")
        }
        val host = uri.host ?: throw RagException(RagErrorCode.EMBEDDING_UNAVAILABLE, "Embedding baseUrl 缺少 Host")
        if (config.allowedHosts.isNotEmpty() && host !in config.allowedHosts) {
            throw RagException(
                RagErrorCode.EMBEDDING_UNAVAILABLE,
                "Embedding Host 不在白名单: $host（Release 环境不得动态注入任意 URL）"
            )
        }
    }

    private suspend fun embedChunkWithRetry(texts: List<String>, apiKey: String?, attempt: Int): List<FloatArray> {
        try {
            return embedChunk(texts, apiKey)
        } catch (e: RagException) {
            // 鉴权失败 / 非法响应不重试。
            throw e
        } catch (e: Exception) {
            val transient = attempt < config.maxRetries
            if (!transient) {
                throw RagException(RagErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 服务不可用（重试耗尽）: ${e.message}", e)
            }
            delay(BACKOFF_BASE_MS * (1L shl attempt))
            return embedChunkWithRetry(texts, apiKey, attempt + 1)
        }
    }

    private suspend fun embedChunk(texts: List<String>, apiKey: String?): List<FloatArray> = withContext(Dispatchers.IO) {
        val body = EmbeddingRequestBody(model = config.modelId, input = texts)
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl.trimEnd('/') + "/embeddings"))
            .timeout(java.time.Duration.ofMillis(config.timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(body)))
        apiKey?.let { requestBuilder.header("Authorization", "Bearer $it") }
        val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            when {
                response.statusCode() in 500..599 -> throw IllegalStateException("Embedding HTTP ${response.statusCode()}")
                response.statusCode() == 401 || response.statusCode() == 403 ->
                    throw RagException(RagErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 鉴权失败 HTTP ${response.statusCode()}")
                else -> throw RagException(RagErrorCode.EMBEDDING_UNAVAILABLE, "Embedding HTTP ${response.statusCode()}")
            }
        }
        val dto = runCatching { json.decodeFromString<EmbeddingResponseDto>(response.body()) }.getOrNull()
            ?: throw RagException(RagErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 响应解析失败")
        if (dto.data.isEmpty() || dto.data.size != texts.size) {
            throw RagException(RagErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 返回数量不一致: ${dto.data.size} != ${texts.size}")
        }
        dto.data.sortedBy { it.index }.map { it.embedding.toFloatArray() }
    }

    private fun validateVectors(vectors: List<FloatArray>, expectedCount: Int) {
        if (vectors.size != expectedCount) {
            throw RagException(RagErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 向量数量不一致: ${vectors.size} != $expectedCount")
        }
        vectors.forEachIndexed { i, v ->
            if (v.size != config.dimension) {
                throw RagException(
                    RagErrorCode.INVALID_VECTOR,
                    "Embedding 维度非法: doc[$i] size=${v.size} expected=${config.dimension}（标记 Provider 配置错误）"
                )
            }
            if (v.any { it.isNaN() || it.isInfinite() }) {
                throw RagException(RagErrorCode.INVALID_VECTOR, "Embedding 含 NaN/Infinity: doc[$i]")
            }
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 64
        const val BACKOFF_BASE_MS = 100L
    }
}

@Serializable
private data class EmbeddingRequestBody(
    val model: String,
    val input: List<String>
)

@Serializable
private data class EmbeddingResponseDto(
    val data: List<EmbeddingDataDto> = emptyList(),
    val model: String? = null
)

@Serializable
private data class EmbeddingDataDto(
    val embedding: List<Float> = emptyList(),
    val index: Int = 0
)
