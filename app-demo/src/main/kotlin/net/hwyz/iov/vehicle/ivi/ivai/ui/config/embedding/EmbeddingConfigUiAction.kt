package net.hwyz.iov.vehicle.ivi.ivai.ui.config.embedding

/** 嵌入模型配置页用户动作（CR-011 补齐）。 */
sealed interface EmbeddingConfigUiAction {
    data class BaseUrlChanged(val value: String) : EmbeddingConfigUiAction
    data class ModelIdChanged(val value: String) : EmbeddingConfigUiAction
    data class ModelVersionChanged(val value: String) : EmbeddingConfigUiAction
    data class DimensionChanged(val value: String) : EmbeddingConfigUiAction
    data class TimeoutMsChanged(val value: String) : EmbeddingConfigUiAction
    data class MaxRetriesChanged(val value: String) : EmbeddingConfigUiAction
    data class BatchSizeChanged(val value: String) : EmbeddingConfigUiAction

    /** 逗号/换行分隔的 Host 白名单（高级选项）。 */
    data class AllowedHostsChanged(val value: String) : EmbeddingConfigUiAction

    data class ApiKeyChanged(val value: String) : EmbeddingConfigUiAction
    data object ToggleApiKeyVisibility : EmbeddingConfigUiAction

    /** 显式请求清除已保存密钥（勾选状态）。 */
    data object ToggleClearKeyRequested : EmbeddingConfigUiAction

    data object TestConnectionClicked : EmbeddingConfigUiAction
    data object SaveClicked : EmbeddingConfigUiAction
    data object DismissMessage : EmbeddingConfigUiAction
}
