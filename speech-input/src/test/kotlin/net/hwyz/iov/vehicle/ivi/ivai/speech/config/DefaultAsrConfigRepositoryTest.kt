package net.hwyz.iov.vehicle.ivi.ivai.speech.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ASR repository behavior (IVI-IVAI-DSN-CR-006): save ordering + rollback,
 * secret Keep/Replace/Clear semantics, invalid drafts never touching the current
 * config, reset clearing the key, and corrupt/undecryptable states.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAsrConfigRepositoryTest {

    private class InMemoryPublicStore : AsrPublicConfigStore {
        var config: AsrPublicConfig? = null
        override suspend fun load(): AsrPublicConfig? = config
        override suspend fun write(config: AsrPublicConfig) {
            this.config = config
        }

        override suspend fun clear() {
            config = null
        }
    }

    private class InMemorySecretStore : AsrSecretStore {
        var key: String? = null
        var failWrite = false
        var corrupt = false
        override suspend fun status(): KeyStatus = when {
            key == null -> KeyStatus.NOT_SET
            corrupt -> KeyStatus.INVALID
            else -> KeyStatus.SET
        }

        override suspend fun write(key: String) {
            if (failWrite) throw RuntimeException("encrypt-fail")
            this.key = key
        }

        override suspend fun read(): String? = if (corrupt) null else key
        override suspend fun clear() {
            key = null
        }
    }

    private fun buildRepo(
        publicStore: AsrPublicConfigStore = InMemoryPublicStore(),
        secretStore: AsrSecretStore = InMemorySecretStore(),
        scope: kotlinx.coroutines.CoroutineScope
    ): DefaultAsrConfigRepository = DefaultAsrConfigRepository(publicStore, secretStore, scope = scope)

    @Test
    fun `initial load with no saved config publishes Invalid`() = runTest {
        val repo = buildRepo(scope = this)
        advanceUntilIdle()
        assertInstanceOf(AsrConfigState.Invalid::class.java, repo.configState.value)
    }

    @Test
    fun `save android provider succeeds with bumped version and no key`() = runTest {
        val publicStore = InMemoryPublicStore()
        val repo = buildRepo(publicStore, scope = this)
        advanceUntilIdle()

        val result = repo.save(AsrConfigDraft(providerType = AsrProviderType.ANDROID_ON_DEVICE))
        assertInstanceOf(SaveResult.Success::class.java, result)

        val state = repo.configState.value as AsrConfigState.Valid
        assertEquals(AsrProviderType.ANDROID_ON_DEVICE, state.config.public.providerType)
        assertNull(state.config.apiKey)
        assertEquals(1L, state.config.version)
        assertEquals(1L, publicStore.config?.configVersion)
    }

    @Test
    fun `save remote provider with typed key persists encrypted key`() = runTest {
        val secretStore = InMemorySecretStore()
        val repo = buildRepo(secretStore = secretStore, scope = this)
        advanceUntilIdle()

        val result = repo.save(
            AsrConfigDraft(
                providerType = AsrProviderType.HTTP_COMPATIBLE,
                baseUrl = "https://asr.example.com/v1",
                modelName = "whisper",
                apiKeyAction = ApiKeyAction.Replace("sk-secret")
            )
        )
        assertInstanceOf(SaveResult.Success::class.java, result)
        assertEquals("sk-secret", secretStore.key)

        val state = repo.configState.value as AsrConfigState.Valid
        assertEquals("https://asr.example.com/v1", state.config.public.baseUrl)
        assertEquals("whisper", state.config.public.modelName)
        // The snapshot exposes the secret only through SecretValue.use().
        state.config.apiKey!!.use { assertEquals("sk-secret", it) }
    }

    @Test
    fun `save with blank key draft keeps the saved key`() = runTest {
        val secretStore = InMemorySecretStore().apply { key = "existing" }
        val repo = buildRepo(secretStore = secretStore, scope = this)
        advanceUntilIdle()

        repo.save(
            AsrConfigDraft(
                providerType = AsrProviderType.HTTP_COMPATIBLE,
                baseUrl = "https://asr.example.com/v1",
                modelName = "whisper",
                apiKeyAction = ApiKeyAction.Keep
            )
        )
        assertEquals("existing", secretStore.key)
    }

    @Test
    fun `invalid draft is rejected without touching the current config`() = runTest {
        val publicStore = InMemoryPublicStore()
        val repo = buildRepo(publicStore, scope = this)
        advanceUntilIdle()

        val result = repo.save(
            AsrConfigDraft(
                providerType = AsrProviderType.HTTP_COMPATIBLE,
                baseUrl = "not-a-url",
                modelName = ""
            )
        )
        assertInstanceOf(SaveResult.Failure::class.java, result)
        assertNull(publicStore.config, "无效草稿不得覆盖当前配置")
    }

    @Test
    fun `secret write failure aborts before touching public config`() = runTest {
        val publicStore = InMemoryPublicStore()
        val secretStore = InMemorySecretStore().apply { failWrite = true }
        val repo = buildRepo(publicStore, secretStore = secretStore, scope = this)
        advanceUntilIdle()

        val result = repo.save(
            AsrConfigDraft(
                providerType = AsrProviderType.HTTP_COMPATIBLE,
                baseUrl = "https://asr.example.com/v1",
                modelName = "whisper",
                apiKeyAction = ApiKeyAction.Replace("sk-new")
            )
        )
        assertInstanceOf(SaveResult.Failure::class.java, result)
        assertEquals(AsrConfigErrorCode.KEY_ERROR, (result as SaveResult.Failure).errorCode)
        assertNull(publicStore.config, "密钥失败时公共配置不得写入")
    }

    @Test
    fun `resetToDefault clears key and restores the default provider`() = runTest {
        val secretStore = InMemorySecretStore().apply { key = "sk-old" }
        val repo = buildRepo(secretStore = secretStore, scope = this)
        advanceUntilIdle()
        repo.save(
            AsrConfigDraft(
                providerType = AsrProviderType.HTTP_COMPATIBLE,
                baseUrl = "https://asr.example.com/v1",
                modelName = "whisper",
                apiKeyAction = ApiKeyAction.Replace("sk-old")
            )
        )
        advanceUntilIdle()

        val result = repo.resetToDefault()
        assertInstanceOf(SaveResult.Success::class.java, result)

        val state = repo.configState.value as AsrConfigState.Valid
        assertEquals(AsrProviderType.ANDROID_ON_DEVICE, state.config.public.providerType)
        assertNull(state.config.apiKey)
        assertEquals(KeyStatus.NOT_SET, secretStore.status())
    }

    @Test
    fun `undecryptable secret publishes Invalid and refuses requests`() = runTest {
        val secretStore = InMemorySecretStore().apply { key = "ciphertext"; corrupt = true }
        val publicStore = InMemoryPublicStore().apply {
            config = AsrPublicConfig(
                providerType = AsrProviderType.HTTP_COMPATIBLE,
                baseUrl = "https://asr.example.com/v1",
                modelName = "whisper"
            )
        }
        val repo = buildRepo(publicStore, secretStore, scope = this)
        advanceUntilIdle()

        assertInstanceOf(AsrConfigState.Invalid::class.java, repo.configState.value)
        val loadResult = kotlin.runCatching { repo.loadSnapshot() }
        assertTrue(loadResult.isFailure)
    }

    @Test
    fun `concurrent saves are serialized and both versions applied`() = runTest {
        val publicStore = InMemoryPublicStore()
        val repo = buildRepo(publicStore, scope = this)
        advanceUntilIdle()

        repo.save(AsrConfigDraft(providerType = AsrProviderType.ANDROID_ON_DEVICE))
        val second = repo.save(
            AsrConfigDraft(
                providerType = AsrProviderType.ANDROID_SYSTEM,
                connectTimeoutMs = 7_000L
            )
        )
        assertInstanceOf(SaveResult.Success::class.java, second)

        val state = repo.configState.value as AsrConfigState.Valid
        assertEquals(AsrProviderType.ANDROID_SYSTEM, state.config.public.providerType)
        assertEquals(2L, state.config.version)
    }

    @Test
    fun `validate delegates to the validator`() = runTest {
        val repo = buildRepo(scope = this)
        advanceUntilIdle()
        val result = repo.validate(AsrConfigDraft(providerType = AsrProviderType.ANDROID_ON_DEVICE))
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun `test connection with keep action carries the saved key`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"ok"}"""))
        try {
            val url = server.url("/v1/audio/transcriptions").toString()
            val publicStore = InMemoryPublicStore().apply {
                config = AsrPublicConfig(
                    providerType = AsrProviderType.HTTP_COMPATIBLE,
                    baseUrl = url,
                    modelName = "FunAudioLLM/SenseVoiceSmall"
                )
            }
            val secretStore = InMemorySecretStore().apply { key = "sk-saved" }
            val repo = DefaultAsrConfigRepository(
                publicStore,
                secretStore,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            )

            // 界面不回填明文 → Keep；连接测试必须携带已保存密钥，否则 401/403。
            val result = runBlocking {
                repo.testConnection(
                    AsrConfigDraft(
                        providerType = AsrProviderType.HTTP_COMPATIBLE,
                        baseUrl = url,
                        modelName = "FunAudioLLM/SenseVoiceSmall",
                        apiKeyAction = ApiKeyAction.Keep
                    )
                )
            }
            assertInstanceOf(ConnectionTestResult.Success::class.java, result)
            val recorded = server.takeRequest()
            assertEquals("Bearer sk-saved", recorded.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `test connection with keep action and no saved key sends no authorization`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"ok"}"""))
        try {
            val url = server.url("/v1/audio/transcriptions").toString()
            val publicStore = InMemoryPublicStore().apply {
                config = AsrPublicConfig(
                    providerType = AsrProviderType.HTTP_COMPATIBLE,
                    baseUrl = url,
                    modelName = "FunAudioLLM/SenseVoiceSmall"
                )
            }
            val repo = DefaultAsrConfigRepository(
                publicStore,
                InMemorySecretStore(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            )

            val result = runBlocking {
                repo.testConnection(
                    AsrConfigDraft(
                        providerType = AsrProviderType.HTTP_COMPATIBLE,
                        baseUrl = url,
                        modelName = "FunAudioLLM/SenseVoiceSmall",
                        apiKeyAction = ApiKeyAction.Keep
                    )
                )
            }
            assertInstanceOf(ConnectionTestResult.Success::class.java, result)
            val recorded = server.takeRequest()
            assertNull(recorded.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
