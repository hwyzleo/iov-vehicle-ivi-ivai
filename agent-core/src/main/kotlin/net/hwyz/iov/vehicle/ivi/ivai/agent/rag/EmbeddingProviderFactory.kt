package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.EmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.HttpCompatibleEmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.embedding.LocalEmbeddingProvider
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig

/**
 * Embedding Provider 装配（CR-011 配置驱动，IVAI-REQ-097「不得在业务代码中硬编码
 * 供应商」）：
 *  - 配置完整（baseUrl + modelId + dimension）→ [HttpCompatibleEmbeddingProvider]；
 *  - 配置为空（用户未配置在线嵌入）→ 回退 [LocalEmbeddingProvider] 哈希桩，
 *    保证 RAG 管线在无在线服务时仍可运行；
 *  - 配置不完整（填了地址但缺 modelId/dimension）→ HTTP Provider.available=false，
 *    上层按 IVAI-RAG-002 降级（索引构建失败 → 非 RAG 检索）。
 *
 * 本类保持纯 JVM，便于单测；Android 侧由 AgentService 注入 [credentialResolver]。
 */
object EmbeddingProviderFactory {

    fun create(
        config: EmbeddingConfig,
        allowInsecureHttp: Boolean,
        credentialResolver: suspend () -> String? = { null }
    ): EmbeddingProvider {
        if (EmbeddingConfigValidator.isBlank(config)) {
            return LocalEmbeddingProvider()
        }
        return HttpCompatibleEmbeddingProvider(
            config = config,
            credentialResolver = credentialResolver,
            allowInsecureHttp = allowInsecureHttp
        )
    }
}
