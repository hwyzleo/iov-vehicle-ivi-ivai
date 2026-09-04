package net.hwyz.iov.vehicle.ivi.ivai.service.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelPublicConfig
import net.hwyz.iov.vehicle.ivi.ivai.model.config.PublicConfigStore

private val Context.publicConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ivai_llm_public_config"
)

/**
 * [PublicConfigStore] backed by AndroidX Preferences DataStore. Non-sensitive
 * public config only — the API key never enters this store (IVI-IVAI-DSN-CR-003).
 *
 * The whole config is stored as a single JSON preference value, so [write] is
 * atomic and a crash can never leave a half-written config.
 */
class DataStorePublicConfigStore(private val context: Context) : PublicConfigStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val configKey = stringPreferencesKey("config")

    override suspend fun load(): ModelPublicConfig? {
        val raw = context.publicConfigDataStore.data
            .map { it[configKey] }
            .first()
        return raw?.let {
            runCatching { json.decodeFromString(ModelPublicConfig.serializer(), it) }.getOrNull()
        }
    }

    override suspend fun write(config: ModelPublicConfig) {
        context.publicConfigDataStore.edit { prefs ->
            prefs[configKey] = json.encodeToString(ModelPublicConfig.serializer(), config)
        }
    }

    override suspend fun clear() {
        context.publicConfigDataStore.edit { prefs -> prefs.remove(configKey) }
    }
}
