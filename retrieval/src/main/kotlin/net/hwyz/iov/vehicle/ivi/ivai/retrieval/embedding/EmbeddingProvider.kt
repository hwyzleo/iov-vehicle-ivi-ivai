package net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.index.EmbeddingLike
import kotlin.math.abs

/**
 * Embedding provider contract (CR-005). The embedding model is configured and
 * upgraded independently from the generative LLM; the index Manifest must
 * record embeddingModelId / dimension / normalization and refuse to load on a
 * mismatch.
 */
interface EmbeddingProvider : EmbeddingLike {
    /** False when the embedding model is not deployed / not loadable. */
    val available: Boolean

    suspend fun embed(texts: List<String>): List<FloatArray>
}

/**
 * Placeholder embedding provider for first-phase validation (CR-005): a
 * deterministic hash-based pseudo-embedding so the vector pipeline (search /
 * manifest dimension checks) is fully exercisable without shipping a real
 * embedding model. Replace with a real on-device embedding model for
 * production; the interface is the seam.
 */
class LocalEmbeddingProvider(
    override val modelId: String = "local-hash-embedding-v1",
    override val dimension: Int = 64
) : EmbeddingProvider {

    override val available: Boolean = true

    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { text ->
        FloatArray(dimension) { d ->
            val h = (text.hashCode() * 31 + d * 2654435761).let { abs(it) }
            ((h % 1000) / 500.0f) - 1.0f
        }
    }
}
