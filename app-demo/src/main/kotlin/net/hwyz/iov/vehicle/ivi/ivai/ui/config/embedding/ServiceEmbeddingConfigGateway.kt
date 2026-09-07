package net.hwyz.iov.vehicle.ivi.ivai.ui.config.embedding

import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagSaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig
import net.hwyz.iov.vehicle.ivi.ivai.service.AgentService

/**
 * [EmbeddingConfigGateway] backed by the shared [AgentService] RAG config
 * repository（CR-011 补齐）。
 */
class ServiceEmbeddingConfigGateway(private val service: AgentService) : EmbeddingConfigGateway {

    override suspend fun load(): EmbeddingConfig =
        service.ragConfigRepository.loadSnapshot().rag.embedding

    override suspend fun keyStatus(): KeyStatus =
        service.ragConfigRepository.embeddingKeyStatus()

    override suspend fun validate(config: EmbeddingConfig): ValidationResult =
        service.ragConfigRepository.validateEmbedding(config)

    override suspend fun testConnection(
        config: EmbeddingConfig,
        apiKeyAction: ApiKeyAction
    ): ConnectionTestResult = service.ragConfigRepository.testEmbeddingConnection(config, apiKeyAction)

    override suspend fun save(
        config: EmbeddingConfig,
        apiKeyAction: ApiKeyAction
    ): RagSaveResult = service.ragConfigRepository.saveEmbedding(config, apiKeyAction)

    override suspend fun resetToDefault(): RagSaveResult =
        service.ragConfigRepository.resetEmbedding()
}
