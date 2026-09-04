package net.hwyz.iov.vehicle.ivi.ivai.service.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelPublicConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the DataStore-backed [PublicConfigStore]
 * (IVI-IVAI-DSN-CR-003). Public config only — no secrets live here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataStorePublicConfigStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanUp() {
        context.getSharedPreferences("ivai_llm_public_config", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `load returns null before anything was written`() = runBlocking {
        assertEquals(null, DataStorePublicConfigStore(context).load())
    }

    @Test
    fun `write then load round trips the public config`() = runBlocking {
        val store = DataStorePublicConfigStore(context)
        val config = ModelPublicConfig(
            schemaVersion = 1,
            baseUrl = "http://192.168.2.170:11434/",
            updatedAt = 1_700_000_000_000L,
            configVersion = 5L
        )
        store.write(config)
        assertEquals(config, store.load())
    }

    @Test
    fun `clear removes the persisted config`() = runBlocking {
        val store = DataStorePublicConfigStore(context)
        store.write(ModelPublicConfig(baseUrl = "http://x:11434", configVersion = 1L))
        store.clear()
        assertNull(store.load())
    }
}
