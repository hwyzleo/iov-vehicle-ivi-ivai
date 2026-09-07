package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.HttpCompatibleEmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.LocalEmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-011 补齐 · Embedding Provider 装配：空配置 → 本地桩；完整配置 → HTTP Provider；
 * 部分配置 → HTTP Provider 但 available=false（由上层按 IVAI-RAG-002 降级）。
 */
class EmbeddingProviderFactoryTest {

    @Test
    fun `空配置回退本地哈希桩`() {
        val provider = EmbeddingProviderFactory.create(EmbeddingConfig(), allowInsecureHttp = true)
        assertInstanceOf(LocalEmbeddingProvider::class.java, provider)
        assertTrue(provider.available)
    }

    @Test
    fun `完整配置返回 HTTP Provider 且可用`() {
        val config = EmbeddingConfig(
            baseUrl = "https://embed.example.com/v1",
            modelId = "embed-v3",
            dimension = 768
        )
        val provider = EmbeddingProviderFactory.create(config, allowInsecureHttp = true)
        assertInstanceOf(HttpCompatibleEmbeddingProvider::class.java, provider)
        assertTrue(provider.available)
    }

    @Test
    fun `部分配置返回 HTTP Provider 但不可用（降级依据）`() {
        val config = EmbeddingConfig(baseUrl = "https://embed.example.com/v1", modelId = "embed-v3", dimension = 0)
        val provider = EmbeddingProviderFactory.create(config, allowInsecureHttp = true)
        assertInstanceOf(HttpCompatibleEmbeddingProvider::class.java, provider)
        assertFalse(provider.available)
    }
}
