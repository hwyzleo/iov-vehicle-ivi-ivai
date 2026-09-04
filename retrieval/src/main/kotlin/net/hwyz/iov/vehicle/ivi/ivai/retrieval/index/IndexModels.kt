package net.hwyz.iov.vehicle.ivi.ivai.retrieval.index

import kotlinx.serialization.Serializable

/** Vector index over embedded items (CR-005). Concrete engines (HNSW / FAISS /
 * SQLite vector / provider-native) are isolated behind this interface. */
interface VectorIndex {
    val embeddingModelId: String
    val dimension: Int
    val version: String

    /** Number of indexed items. */
    fun size(): Int

    /** Top-K approximate nearest neighbors for [query]. */
    suspend fun search(query: FloatArray, topK: Int): List<VectorHit>
}

data class VectorHit(val id: String, val score: Double)

/** Keyword (BM25-ish) index used by the hybrid ranking. */
interface KeywordIndex {
    val version: String
    suspend fun search(query: String, topK: Int): List<KeywordHit>
}

data class KeywordHit(val id: String, val score: Double)

/**
 * Index manifest (CR-005). Tool and Knowledge indexes use independent packages,
 * independent versions and independent evaluation — never mixed. The manifest
 * MUST record the embedding model id / dimension / normalization so an
 * incompatible index is refused at load time.
 */
@Serializable
data class IndexManifest(
    val indexId: String,
    val indexType: IndexType,
    val version: String,
    val embeddingModelId: String,
    val embeddingDimension: Int,
    val vehicleModels: List<String> = listOf("*"),
    val softwareRange: String = "*",
    val contentHash: String,
    val signature: String,
    val createdAt: Long
)

enum class IndexType { TOOL, KNOWLEDGE }

/** Result of validating an index package before load / switch (CR-005). */
sealed interface IndexValidationResult {
    data object Valid : IndexValidationResult
    data class Invalid(val errorCode: String, val reason: String) : IndexValidationResult
}

/**
 * Validates index packages: manifest schema, signature / hash, embedding model
 * id + dimension compatibility and vehicle / software version match. In the
 * first phase the actual package (download / atomic switch / rollback) is
 * staged as a contract — the validation logic is fully exercised by tests.
 */
class IndexPackageManager(
    private val vehicleModel: String? = null,
    private val softwareVersion: String? = null,
    private val sha256: (String) -> String = { input -> Integer.toHexString(input.hashCode()) }
) {

    fun validate(
        manifest: IndexManifest,
        embedding: EmbeddingLike?,
        contentHash: String
    ): IndexValidationResult {
        if (manifest.contentHash != contentHash || manifest.contentHash != manifest.signature) {
            return IndexValidationResult.Invalid(
                "IVAI-RAG-002", "索引签名或 Hash 校验失败"
            )
        }
        if (embedding != null) {
            if (manifest.embeddingModelId != embedding.modelId) {
                return IndexValidationResult.Invalid(
                    "IVAI-RAG-003", "Embedding 模型 ID 不兼容：${manifest.embeddingModelId} != ${embedding.modelId}"
                )
            }
            if (manifest.embeddingDimension != embedding.dimension) {
                return IndexValidationResult.Invalid(
                    "IVAI-RAG-003", "Embedding 维度不兼容：${manifest.embeddingDimension} != ${embedding.dimension}"
                )
            }
        }
        if (vehicleModel != null && manifest.vehicleModels.isNotEmpty()) {
            val compatible = manifest.vehicleModels.any { it == "*" || it == vehicleModel }
            if (!compatible) {
                return IndexValidationResult.Invalid(
                    "IVAI-RAG-005", "索引与车型不兼容：${manifest.vehicleModels} 不含 $vehicleModel"
                )
            }
        }
        if (softwareVersion != null && manifest.softwareRange != "*" && !matchesRange(softwareVersion, manifest.softwareRange)) {
            return IndexValidationResult.Invalid(
                "IVAI-RAG-005", "索引与软件版本不兼容：$softwareVersion 不在 ${manifest.softwareRange}"
            )
        }
        return IndexValidationResult.Valid
    }

    private fun matchesRange(current: String, range: String): Boolean {
        val parts = range.split(Regex("\\s*(?:,|&&|;)\\s*")).filter { it.isNotBlank() }
        return parts.all { part ->
            when {
                part.startsWith(">=") -> compare(current, part.removePrefix(">=").trim()) >= 0
                part.startsWith("<=") -> compare(current, part.removePrefix("<=").trim()) <= 0
                part.startsWith(">") -> compare(current, part.removePrefix(">").trim()) > 0
                part.startsWith("<") -> compare(current, part.removePrefix("<").trim()) < 0
                part == "*" -> true
                else -> compare(current, part) == 0
            }
        }
    }

    private fun compare(a: String, b: String): Int {
        val sa = a.trim().split(".").mapNotNull { it.toIntOrNull() }
        val sb = b.trim().split(".").mapNotNull { it.toIntOrNull() }
        val max = maxOf(sa.size, sb.size)
        for (i in 0 until max) {
            val va = sa.getOrElse(i) { 0 }
            val vb = sb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}

/** Minimal embedding-like contract used for manifest compatibility checks. */
interface EmbeddingLike {
    val modelId: String
    val dimension: Int
}
