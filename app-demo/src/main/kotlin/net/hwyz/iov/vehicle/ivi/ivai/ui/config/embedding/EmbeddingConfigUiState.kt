package net.hwyz.iov.vehicle.ivi.ivai.ui.config.embedding

import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus

/**
 * 嵌入模型配置页状态（CR-011 补齐）。
 *
 * 安全规则：已保存密钥绝不回填明文，仅暴露 [keyStatus]；[apiKeyDraft] 只保存
 * 本会话用户输入；替换与清除是显式且独立的动作。
 */
data class EmbeddingConfigUiState(
    val baseUrl: String = "",
    val modelId: String = "",
    val modelVersion: String = "",
    val dimension: String = "0",
    val timeoutMs: String = "10000",
    val maxRetries: String = "2",
    val batchSize: String = "16",
    val allowedHosts: String = "",
    val keyStatus: KeyStatus = KeyStatus.NOT_SET,
    val apiKeyDraft: String = "",
    val showApiKey: Boolean = false,
    val clearKeyRequested: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val isTesting: Boolean = false,
    val isSaving: Boolean = false,
    val validationErrors: Map<String, String> = emptyMap(),
    val message: String? = null,
    /** 是否已从仓库预填过一次（避免后续发射覆盖用户正在输入的内容）。 */
    val everPrefilled: Boolean = false
)
