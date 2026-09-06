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
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigRepository
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigState
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagRuntimeConfig
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagSaveResult

private val Context.ragConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ivai_rag_config"
)

/**
 * [RagConfigRepository] backed by AndroidX Preferences DataStore
 * (IVI-IVAI-DSN-CR-005). RAG 开关属于 Agent/Retrieval 运行配置，独立持久化，
 * 与 Ollama/OpenAI ModelProvider 私有配置无关。
 */
class DataStoreRagConfigRepository(
    private val context: Context,
    private val scope: CoroutineScope
) : RagConfigRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val configKey = stringPreferencesKey("rag_config")

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
