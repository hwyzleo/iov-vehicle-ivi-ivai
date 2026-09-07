package net.hwyz.iov.vehicle.ivi.ivai.service.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.EmbeddingConfigValidator
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.EmbeddingConnectionTester
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigState
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagSaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretStore
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig

private val Context.ragConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ivai_rag_config"
)

/**
 * [RagConfigRepository] backed by AndroidX Preferences DataStore
 * (IVI-IVAI-DSN-CR-005 + CR-011 补齐). RAG 开关与 Embedding Provider 配置属于
 * Agent/Retrieval 运行配置，独立持久化，与 Ollama/OpenAI ModelProvider 私有配置无关。
 *
 * Embedding 密钥（credentialRef 指向 Keystore）经 [embeddingSecretStore] 加密切分，
 * 绝不写入 DataStore 明文；保存顺序与 ModelConfigRepository 一致：先校验、再落密钥、
 * 最后落公共配置，失败不污染当前生效配置。
 */
class DataStoreRagConfigRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val embeddingSecretStore: SecretStore,
    private val allowInsecureHttp: Boolean = true
) : RagConfigRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val configKey = stringPreferencesKey("rag_config")
    private val embeddingValidator = EmbeddingConfigValidator(allowInsecureHttp)
    private val embeddingTester = EmbeddingConnectionTester(
        validator = embeddingValidator
    )

    private val _configState = MutableStateFlow<RagConfigState>(RagConfigState.Loading)
    override val configState: StateFlow<RagConfigState> = _configState.asStateFlow()

    init {
        scope.launch { reload() }
    }

    override suspend fun loadSnapshot(): RagRuntimeConfig {
        val current = _configState.value
        if (current is RagConfigState.Valid) return current.config
        return readStore()
    }

    override suspend fun save(draft: RagConfigDraft): RagSaveResult {
        val previous = runCatching { readStore() }.getOrNull() ?: RagRuntimeConfig()
        val newVersion = previous.version + 1L
        val newConfig = RagRuntimeConfig(
            schemaVersion = RagRuntimeConfig.CURRENT_SCHEMA_VERSION,
            enabled = draft.enabled,
            toolRagEnabled = draft.toolRagEnabled,
            knowledgeRagEnabled = draft.knowledgeRagEnabled,
            toolTopK = draft.toolTopK,
            knowledgeTopK = draft.knowledgeTopK,
            version = newVersion,
            rag = draft.rag
        )
        return try {
            writeStore(newConfig)
            _configState.value = RagConfigState.Valid(newConfig)
            RagSaveResult.Success(newVersion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RagSaveResult.Failure(
                RagConfigErrorCode.PERSISTENCE_FAILED,
                "RAG 配置持久化失败：${e.message}"
            )
        }
    }

    override suspend fun resetToDefault(): RagSaveResult {
        val previous = runCatching { readStore() }.getOrNull() ?: RagRuntimeConfig()
        val newVersion = previous.version + 1L
        val defaults = RagRuntimeConfig(version = newVersion)
        return try {
            writeStore(defaults)
            _configState.value = RagConfigState.Valid(defaults)
            RagSaveResult.Success(newVersion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RagSaveResult.Failure(
                RagConfigErrorCode.PERSISTENCE_FAILED,
                "RAG 配置重置失败：${e.message}"
            )
        }
    }

    // ---- CR-011 补齐：Embedding Provider 配置（IVAI-REQ-097） ----

    override suspend fun validateEmbedding(config: EmbeddingConfig): ValidationResult =
        embeddingValidator.validate(config)

    override suspend fun embeddingKeyStatus(): KeyStatus = embeddingSecretStore.status()

    override suspend fun testEmbeddingConnection(
        config: EmbeddingConfig,
        apiKeyAction: ApiKeyAction
    ): ConnectionTestResult {
        // Keep 时回填已保存密钥（与 ModelConnectionTester 同一修复，避免缺 Authorization 头）。
        val effectiveKey = when (apiKeyAction) {
            ApiKeyAction.Keep -> runCatching { embeddingSecretStore.read() }.getOrNull()
            is ApiKeyAction.Replace -> apiKeyAction.value
            ApiKeyAction.Clear -> null
        }
        return embeddingTester.test(config, effectiveKey)
    }

    override suspend fun saveEmbedding(
        config: EmbeddingConfig,
        apiKeyAction: ApiKeyAction
    ): RagSaveResult {
        val validation = embeddingValidator.validate(config)
        if (validation is ValidationResult.Invalid) {
            val first = validation.errors.first()
            return RagSaveResult.Failure(RagConfigErrorCode.INVALID_CONFIG, first.message)
        }

        // 1. 密钥先行——失败则中止，公共配置不动。
        try {
            when (apiKeyAction) {
                ApiKeyAction.Keep -> Unit
                is ApiKeyAction.Replace -> embeddingSecretStore.write(apiKeyAction.value)
                ApiKeyAction.Clear -> embeddingSecretStore.clear()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RagSaveResult.Failure(
                RagConfigErrorCode.ENCRYPTION_FAILED,
                "Embedding 密钥加密或保存失败"
            )
        }

        // 2. 合并进当前配置（不影响 RAG 开关与 Top-K），递增版本并发布。
        val current = runCatching { readStore() }.getOrNull() ?: RagRuntimeConfig()
        val newVersion = current.version + 1L
        val newConfig = current.copy(
            version = newVersion,
            rag = current.rag.copy(embedding = config)
        )
        return try {
            writeStore(newConfig)
            _configState.value = RagConfigState.Valid(newConfig)
            RagSaveResult.Success(newVersion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RagSaveResult.Failure(
                RagConfigErrorCode.PERSISTENCE_FAILED,
                "Embedding 配置持久化失败：${e.message}"
            )
        }
    }

    override suspend fun resetEmbedding(): RagSaveResult {
        // 清空在线配置（回退本地桩）并清除密钥；保留 RAG 开关与 Top-K。
        try {
            embeddingSecretStore.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RagSaveResult.Failure(
                RagConfigErrorCode.ENCRYPTION_FAILED,
                "清除 Embedding 密钥失败"
            )
        }
        val current = runCatching { readStore() }.getOrNull() ?: RagRuntimeConfig()
        val newVersion = current.version + 1L
        val newConfig = current.copy(
            version = newVersion,
            rag = current.rag.copy(embedding = EmbeddingConfig())
        )
        return try {
            writeStore(newConfig)
            _configState.value = RagConfigState.Valid(newConfig)
            RagSaveResult.Success(newVersion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RagSaveResult.Failure(
                RagConfigErrorCode.PERSISTENCE_FAILED,
                "Embedding 配置重置失败：${e.message}"
            )
        }
    }

    private suspend fun reload() {
        _configState.value = try {
            RagConfigState.Valid(readStore())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RagConfigState.Invalid(
                "RAG 配置读取失败：${e.message}",
                RagConfigErrorCode.PERSISTENCE_FAILED
            )
        }
    }

    private suspend fun readStore(): RagRuntimeConfig {
        val raw = context.ragConfigDataStore.data.map { it[configKey] }.first()
        return raw?.let {
            runCatching { json.decodeFromString(RagRuntimeConfig.serializer(), it) }.getOrNull()
        } ?: RagRuntimeConfig()
    }

    private suspend fun writeStore(config: RagRuntimeConfig) {
        context.ragConfigDataStore.edit { prefs ->
            prefs[configKey] = json.encodeToString(RagRuntimeConfig.serializer(), config)
        }
    }
}
