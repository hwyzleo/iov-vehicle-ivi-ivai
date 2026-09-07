package net.hwyz.iov.vehicle.ivi.ivai.ui.config.embedding

import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagSaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig

/**
 * 嵌入模型配置 Gateway（CR-011 补齐 · 仿 ModelConfigGateway）：暴露持久化的
 * Embedding Provider 配置、密钥状态与连通性测试，ViewModel 保持可单测。
 */
interface EmbeddingConfigGateway {

    /** 当前持久化的 Embedding 配置（未配置时为空默认值）。 */
    suspend fun load(): EmbeddingConfig

    /** 已保存的 Embedding 密钥状态——绝不回显明文。 */
    suspend fun keyStatus(): KeyStatus

    /** 校验配置而不落库（全空视为合法回退状态）。 */
    suspend fun validate(config: EmbeddingConfig): ValidationResult

    /** 只读连通性测试，不落库。 */
    suspend fun testConnection(config: EmbeddingConfig, apiKeyAction: ApiKeyAction): ConnectionTestResult

    /** 校验 + 持久化（含密钥替换/清除），不影响 RAG 开关与 Top-K。 */
    suspend fun save(config: EmbeddingConfig, apiKeyAction: ApiKeyAction): RagSaveResult

    /** 恢复默认：清空在线嵌入配置与密钥（回退本地桩）。 */
    suspend fun resetToDefault(): RagSaveResult
}
