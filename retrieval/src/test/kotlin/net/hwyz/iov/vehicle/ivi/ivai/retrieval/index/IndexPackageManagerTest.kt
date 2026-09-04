package net.hwyz.iov.vehicle.ivi.ivai.retrieval.index

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.LocalEmbeddingProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-005 验证设计 · 部署与故障：Hash/签名、Embedding 维度、车型与软件版本
 * 不匹配时拒绝加载索引。
 */
class IndexPackageManagerTest {

    private val embedding = LocalEmbeddingProvider(modelId = "embed-a", dimension = 64)

    private fun manifest(
        embeddingModelId: String = "embed-a",
        dimension: Int = 64,
        vehicleModels: List<String> = listOf("*"),
        softwareRange: String = ">=0.1.0",
        contentHash: String = "hash-1",
        signature: String = "hash-1"
    ) = IndexManifest(
        indexId = "tool-1",
        indexType = IndexType.TOOL,
        version = "1.0.0",
        embeddingModelId = embeddingModelId,
        embeddingDimension = dimension,
        vehicleModels = vehicleModels,
        softwareRange = softwareRange,
        contentHash = contentHash,
        signature = signature,
        createdAt = 0L
    )

    @Test
    fun `合法索引通过校验`() {
        val manager = IndexPackageManager(vehicleModel = null, softwareVersion = "0.1.0")
        val result = manager.validate(manifest(), embedding, contentHash = "hash-1")
        assertInstanceOf(IndexValidationResult.Valid::class.java, result)
    }

    @Test
    fun `Hash 或签名不匹配拒绝加载`() {
        val manager = IndexPackageManager()
        val badHash = manager.validate(manifest(), embedding, contentHash = "tampered")
        assertInstanceOf(IndexValidationResult.Invalid::class.java, badHash)
        assertEquals("IVAI-RAG-002", (badHash as IndexValidationResult.Invalid).errorCode)

        val badSignature = manager.validate(manifest(signature = "forged"), embedding, contentHash = "hash-1")
        assertEquals("IVAI-RAG-002", (badSignature as IndexValidationResult.Invalid).errorCode)
    }

    @Test
    fun `Embedding 维度不兼容拒绝加载`() {
        val manager = IndexPackageManager()
        val result = manager.validate(manifest(dimension = 128), embedding, contentHash = "hash-1")
        assertInstanceOf(IndexValidationResult.Invalid::class.java, result)
        assertEquals("IVAI-RAG-003", (result as IndexValidationResult.Invalid).errorCode)
    }

    @Test
    fun `Embedding 模型 ID 不兼容拒绝加载`() {
        val manager = IndexPackageManager()
        val result = manager.validate(manifest(embeddingModelId = "embed-b"), embedding, contentHash = "hash-1")
        assertEquals("IVAI-RAG-003", (result as IndexValidationResult.Invalid).errorCode)
    }

    @Test
    fun `车型不匹配拒绝加载`() {
        val manager = IndexPackageManager(vehicleModel = "model-x", softwareVersion = "0.1.0")
        val result = manager.validate(
            manifest(vehicleModels = listOf("model-a")),
            embedding, contentHash = "hash-1"
        )
        assertEquals("IVAI-RAG-005", (result as IndexValidationResult.Invalid).errorCode)
    }

    @Test
    fun `软件版本不匹配拒绝加载`() {
        val manager = IndexPackageManager(vehicleModel = null, softwareVersion = "0.0.5")
        val result = manager.validate(manifest(), embedding, contentHash = "hash-1")
        assertEquals("IVAI-RAG-005", (result as IndexValidationResult.Invalid).errorCode)
    }

    @Test
    fun `车型通配符与版本范围通过校验`() {
        val manager = IndexPackageManager(vehicleModel = "any", softwareVersion = "0.2.0")
        val result = manager.validate(manifest(), embedding, contentHash = "hash-1")
        assertTrue(result is IndexValidationResult.Valid)
    }
}
