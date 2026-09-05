package net.hwyz.iov.vehicle.ivi.ivai.speech.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.asrPublicConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ivai_asr_public_config"
)

/**
 * [AsrPublicConfigStore] backed by AndroidX Preferences DataStore. Non-sensitive
 * public config only — the ASR API key never enters this store
 * (IVI-IVAI-DSN-CR-006). The whole config is a single JSON value, so [write] is
 * atomic and a crash can never leave a half-written config.
 */
class AsrDataStorePublicConfigStore(private val context: Context) : AsrPublicConfigStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val configKey = stringPreferencesKey("config")

    override suspend fun load(): AsrPublicConfig? {
        val raw = context.asrPublicConfigDataStore.data
            .map { it[configKey] }
            .first()
        return raw?.let {
            runCatching { json.decodeFromString(AsrPublicConfig.serializer(), it) }.getOrNull()
        }
    }

    override suspend fun write(config: AsrPublicConfig) {
        context.asrPublicConfigDataStore.edit { prefs ->
            prefs[configKey] = json.encodeToString(AsrPublicConfig.serializer(), config)
        }
    }

    override suspend fun clear() {
        context.asrPublicConfigDataStore.edit { prefs -> prefs.remove(configKey) }
    }
}
