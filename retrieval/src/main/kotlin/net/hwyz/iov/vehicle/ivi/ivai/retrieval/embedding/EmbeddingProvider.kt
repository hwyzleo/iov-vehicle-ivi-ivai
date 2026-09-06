package net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.index.EmbeddingLike
import kotlin.math.abs

/**
 * Embedding provider 契约（IVI-IVAI-DSN-CR-011）。
 *
 * The embedding model is configured and upgraded independently from the
 * generative LLM; the index Manifest must record providerType / modelId /
 * modelVersion / dimension and refuse to load on a mismatch.
 *
 * 首期注册 [HttpCompatibleEmbeddingProvider]（在线）；[LocalEmbeddingProvider]
 * 保留为测试 / STUB 哈希实现，可随时替换为本地 Embedding 而不改变上层契约。
 */
interface EmbeddingProvider : EmbeddingLike {
    /** 模型描述：兼容键的一部分（CR-011）。 */
    val descriptor: EmbeddingModelDescriptor

    override val modelId: String get() = descriptor.modelId
    override val dimension: Int get() = descriptor.dimension

    /** False when the embedding model is not deployed / not loadable. */
    val available: Boolean

    suspend fun embed(request: EmbeddingRequest): EmbeddingResponse
}

/**
 * Placeholder embedding provider for first-phase validation (CR-005, evolved in
 * CR-011): a deterministic hash-based pseudo-embedding so the vector pipeline
 * (search / manifest dimension checks) is fully exercisable without shipping a
 * real embedding model. Replace with a real on-device embedding model for
 * production; the interface is the seam.
 */
class LocalEmbeddingProvider(
    override val descriptor: EmbeddingModelDescriptor = EmbeddingModelDescriptor(
        providerType = "LOCAL",
        modelId = "local-hash-embedding-v1",
        modelVersion = null,
        dimension = 64
    )
) : EmbeddingProvider {

    override val available: Boolean = true

    override suspend fun embed(request: EmbeddingRequest): EmbeddingResponse {
        val vectors = request.texts.map { text ->
            FloatArray(descriptor.dimension) { d ->
                val h = (text.hashCode() * 31 + d * 2654435761).let { abs(it) }
                ((h % 1000) / 500.0f) - 1.0f
            }
        }
        return EmbeddingResponse(vectors, descriptor.modelId, descriptor.dimension)
    }
}
