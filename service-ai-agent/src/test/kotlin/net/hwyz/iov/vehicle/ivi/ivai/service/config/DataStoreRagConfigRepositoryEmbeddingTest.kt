package net.hwyz.iov.vehicle.ivi.ivai.service.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigDraft
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagConfigErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agent.rag.RagSaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretStore
import net.hwyz.iov.vehicle.ivi.ivai.retrieval.rag.EmbeddingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-011 补齐 · DataStoreRagConfigRepository 的 Embedding 配置持久化：
 * 保存/回读/版本自增、密钥 Keep/Replace/Clear、非法配置拒绝、重置回退。
 *
 * 断言均相对测试内写入的前后状态（Preferences DataStore 单例可能跨测试残留，
 * 不做绝对初始值假设）。密钥经注入的内存 [SecretStore] 隔离，不落在明文配置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataStoreRagConfigRepositoryEmbeddingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 内存密钥实现，隔离 Android Keystore。 */
    private class InMemorySecretStore : SecretStore {
        private var value: String? = null
        override suspend fun status(): KeyStatus = if (value == null) KeyStatus.NOT_SET else KeyStatus.SET
        override suspend fun write(key: String) { value = key }
        override suspend fun read(): String? = value
        override suspend fun clear() { value = null }
    }

    private val secretStore = InMemorySecretStore()

    @Before
    fun cleanUp() {
        File(context.filesDir, "datastore").deleteRecursively()
        runBlocking { secretStore.clear() }
    }

    private fun repo() = DataStoreRagConfigRepository(
        context,
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        embeddingSecretStore = secretStore,
        allowInsecureHttp = true
    )

    private val config = EmbeddingConfig(
        baseUrl = "https://embed.example.com/v1",
        modelId = "embed-v3",
        dimension = 768,
        timeoutMs = 10_000L,
        maxRetries = 2,
        batchSize = 16
    )

    @Test
    fun `saveEmbedding round trips and bumps version without touching switches`() = runBlocking {
        val r = repo()
        val before = r.loadSnapshot()
        val save = r.saveEmbedding(config, ApiKeyAction.Keep)
        assertTrue(save is RagSaveResult.Success)

        val loaded = r.loadSnapshot()
        assertEquals(config, loaded.rag.embedding)
        assertEquals(before.version + 1L, loaded.version)

        // 开关与 Top-K 保持原样，未被 Embedding 保存影响。
        assertEquals(before.enabled, loaded.enabled)
        assertEquals(before.toolTopK, loaded.toolTopK)
    }

    @Test
    fun `saveEmbedding with Replace then Clear manages the key`() = runBlocking {
        val r = repo()
        r.saveEmbedding(config, ApiKeyAction.Replace("sk-embed"))
        assertEquals(KeyStatus.SET, r.embeddingKeyStatus())

        r.saveEmbedding(config, ApiKeyAction.Clear)
        assertEquals(KeyStatus.NOT_SET, r.embeddingKeyStatus())
    }

    @Test
    fun `invalid partial config is rejected and key untouched`() = runBlocking {
        val r = repo()
        val before = r.loadSnapshot()
        val bad = config.copy(modelId = "")
        val result = r.saveEmbedding(bad, ApiKeyAction.Replace("sk-should-not-persist"))

        assertTrue(result is RagSaveResult.Failure)
        assertEquals(RagConfigErrorCode.INVALID_CONFIG, (result as RagSaveResult.Failure).errorCode)
        assertEquals(KeyStatus.NOT_SET, r.embeddingKeyStatus())
        // 版本不变（未落库）。
        assertEquals(before.version, r.loadSnapshot().version)
        assertNotEquals(bad, r.loadSnapshot().rag.embedding)
    }

    @Test
    fun `resetEmbedding clears config and key but keeps switches`() = runBlocking {
        val r = repo()
        // 先打开开关并配置 Embedding，验证相互不影响、重置后开关保留。
        assertTrue(r.save(RagConfigDraft(enabled = true)) is RagSaveResult.Success)
        assertTrue(r.saveEmbedding(config, ApiKeyAction.Replace("sk-embed")) is RagSaveResult.Success)

        val before = r.loadSnapshot()
        assertEquals(config, before.rag.embedding)
        assertEquals(true, before.enabled)

        assertTrue(r.resetEmbedding() is RagSaveResult.Success)
        val loaded = r.loadSnapshot()
        assertEquals(EmbeddingConfig(), loaded.rag.embedding)
        assertEquals(KeyStatus.NOT_SET, r.embeddingKeyStatus())
        assertEquals(true, loaded.enabled)
    }
}
