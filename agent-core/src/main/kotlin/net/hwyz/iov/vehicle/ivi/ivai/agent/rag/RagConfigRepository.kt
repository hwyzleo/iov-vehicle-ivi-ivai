package net.hwyz.iov.vehicle.ivi.ivai.agent.rag

import kotlinx.coroutines.flow.StateFlow
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig

/** Result of saving / resetting the RAG configuration. */
sealed interface RagSaveResult {
    data class Success(val configVersion: Long) : RagSaveResult
    data class Failure(val errorCode: String, val message: String) : RagSaveResult
}

/** RAG config persistence errors (IVI-IVAI-DSN-CR-005). */
object RagConfigErrorCode {
    const val PERSISTENCE_FAILED = "IVAI-RAG-CONFIG-001"
    const val MISSING_REQUIRED = "IVAI-RAG-CONFIG-002"
    const val VERSION_INCOMPATIBLE = "IVAI-RAG-CONFIG-003"
    const val INVALID_CONFIG = "IVAI-RAG-CONFIG-004"
    const val ENCRYPTION_FAILED = "IVAI-RAG-CONFIG-005"
}

/** Published RAG configuration state consumed by the UI and diagnostics. */
sealed interface RagConfigState {
    /** Initial load in progress. */
    data object Loading : RagConfigState

    /** A valid RAG configuration is effective. */
    data class Valid(val config: RagRuntimeConfig) : RagConfigState

    /** Persisted config is unusable; falls back to defaults. */
    data class Invalid(val reason: String, val errorCode: String) : RagConfigState
}

/**
 * Central RAG runtime configuration repository (IVI-IVAI-DSN-CR-005).
 * The concrete persistence (DataStore) lives in the Android service module.
 */
interface RagConfigRepository {

    /** Published configuration state for UI and diagnostics. */
    val configState: StateFlow<RagConfigState>

    /** Current effective configuration; defaults when nothing persisted yet. */
    suspend fun loadSnapshot(): RagRuntimeConfig

    /** Validates + persists the draft and bumps the configVersion. */
    suspend fun save(draft: RagConfigDraft): RagSaveResult

    /** Confirmed reset: back to defaults (disabled). */
    suspend fun resetToDefault(): RagSaveResult

    // ---- CR-011 补齐：Embedding Provider 配置（IVAI-REQ-097 可配置，不得硬编码供应商）----

    /** 校验 Embedding 配置而不落库（全空视为合法回退状态）。 */
    suspend fun validateEmbedding(config: EmbeddingConfig): ValidationResult

    /** 已保存的 Embedding 密钥状态——绝不回显明文。 */
    suspend fun embeddingKeyStatus(): KeyStatus

    /** 对草稿配置执行只读连通性测试，不落库。 */
    suspend fun testEmbeddingConnection(
        config: EmbeddingConfig,
        apiKeyAction: ApiKeyAction
    ): ConnectionTestResult

    /** 校验 + 持久化 Embedding 配置（含密钥替换/清除），不影响 RAG 开关与 Top-K。 */
    suspend fun saveEmbedding(config: EmbeddingConfig, apiKeyAction: ApiKeyAction): RagSaveResult

    /** 恢复默认：清空在线 Embedding 配置与密钥（回退本地桩），保留 RAG 开关。 */
    suspend fun resetEmbedding(): RagSaveResult
}
