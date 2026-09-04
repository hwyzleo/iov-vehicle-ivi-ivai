package net.hwyz.iov.vehicle.ivi.ivai.model.config

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProviderType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DefaultModelConfigRepository] using in-memory stores:
 * save/keep/replace/clear, rollback on failure, version bumps, reset, and the
 * Invalid-state refusal path (IVI-IVAI-DSN-CR-003).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultModelConfigRepositoryTest {

    private class InMemoryPublicStore(
        var stored: ModelPublicConfig? = null,
        var failWrites: Boolean = false
    ) : PublicConfigStore {
        override suspend fun load(): ModelPublicConfig? = stored
        override suspend fun write(config: ModelPublicConfig) {
            if (failWrites) throw IOException("disk full")
            stored = config
        }
        override suspend fun clear() {
            stored = null
        }
    }

    private class InMemorySecretStore(
        var key: String? = null,
        var status: KeyStatus = KeyStatus.NOT_SET,
        var failWrites: Boolean = false
    ) : SecretStore {
        override suspend fun status(): KeyStatus = status
        override suspend fun write(key: String) {
            if (failWrites) throw IOException("keystore write failed")
            this.key = key
            status = KeyStatus.SET
        }
        override suspend fun read(): String? = key
        override suspend fun clear() {
            key = null
            status = KeyStatus.NOT_SET
        }
    }

    private fun buildRepo(
        public: PublicConfigStore,
        secret: SecretStore,
        testScope: TestScope,
        defaultUrl: String = "http://localhost:11434"
    ): DefaultModelConfigRepository =
        DefaultModelConfigRepository(public, secret, defaultUrl, scope = testScope)

    @Test
    fun `initial load with no saved config publishes Invalid`() = runTest {
        val repo = buildRepo(InMemoryPublicStore(), InMemorySecretStore(), this)
        advanceUntilIdle()

        val state = repo.configState.value
        val invalid = assertInstanceOf(ModelConfigState.Invalid::class.java, state)
        assertEquals(ModelConfigErrorCode.MISSING_REQUIRED, invalid.errorCode)
        val error = try {
            repo.loadSnapshot()
            null
        } catch (e: ModelConfigException) {
            e
        }
        assertEquals(ModelConfigErrorCode.MISSING_REQUIRED, error?.errorCode)
    }

    @Test
    fun `save with replace persists key and baseUrl and bumps version`() = runTest {
        val public = InMemoryPublicStore()
        val secret = InMemorySecretStore()
        val repo = buildRepo(public, secret, this)
        advanceUntilIdle()

        val result = repo.save(ModelConfigDraft(baseUrl = "http://192.168.1.10:11434", apiKeyAction = ApiKeyAction.Replace("sk-secret")))
        val success = assertInstanceOf(SaveResult.Success::class.java, result)
        assertEquals(1L, success.configVersion)

        assertEquals("http://192.168.1.10:11434/", public.stored!!.baseUrl)
        assertEquals("sk-secret", secret.key)
        assertEquals(KeyStatus.SET, secret.status)

        val state = repo.configState.value
        val valid = assertInstanceOf(ModelConfigState.Valid::class.java, state)
        assertEquals("192.168.1.10", valid.config.baseUrl.host)
        assertEquals(1L, valid.config.version)
        assertNotNull(valid.config.apiKey)
        valid.config.apiKey!!.use { assertEquals("sk-secret", it) }

        // loadSnapshot returns the freshly published snapshot.
        val snap = repo.loadSnapshot()
        assertEquals("192.168.1.10", snap.baseUrl.host)
        assertEquals(1L, snap.version)
    }

    @Test
    fun `save with keep preserves the existing key`() = runTest {
        val public = InMemoryPublicStore()
        val secret = InMemorySecretStore()
        val repo = buildRepo(public, secret, this)
        advanceUntilIdle()

        repo.save(ModelConfigDraft(baseUrl = "http://a:11434", apiKeyAction = ApiKeyAction.Replace("orig-key")))
        val result = repo.save(ModelConfigDraft(baseUrl = "http://b:11434", apiKeyAction = ApiKeyAction.Keep))

        val success = assertInstanceOf(SaveResult.Success::class.java, result)
        assertEquals(2L, success.configVersion)
        assertEquals("orig-key", secret.key)
        assertEquals("http://b:11434/", public.stored!!.baseUrl)
    }

    @Test
    fun `save with clear removes the saved key`() = runTest {
        val public = InMemoryPublicStore()
        val secret = InMemorySecretStore()
        val repo = buildRepo(public, secret, this)
        advanceUntilIdle()

        repo.save(ModelConfigDraft(baseUrl = "http://a:11434", apiKeyAction = ApiKeyAction.Replace("orig-key")))
        val result = repo.save(ModelConfigDraft(baseUrl = "http://b:11434", apiKeyAction = ApiKeyAction.Clear))

        assertInstanceOf(SaveResult.Success::class.java, result)
        assertNull(secret.key)
        assertEquals(KeyStatus.NOT_SET, secret.status)
        assertEquals("http://b:11434/", public.stored!!.baseUrl)
    }

    @Test
    fun `save failure on public write rolls back secret and keeps old config valid`() = runTest {
        val public = InMemoryPublicStore()
        val secret = InMemorySecretStore()
        val repo = buildRepo(public, secret, this)
        advanceUntilIdle()

        repo.save(ModelConfigDraft(baseUrl = "http://old:11434", apiKeyAction = ApiKeyAction.Replace("old-key")))

        public.failWrites = true
        val result = repo.save(ModelConfigDraft(baseUrl = "http://new:11434", apiKeyAction = ApiKeyAction.Replace("new-key")))
        val failure = assertInstanceOf(SaveResult.Failure::class.java, result)
        assertEquals(ModelConfigErrorCode.PERSISTENCE_FAILED, failure.errorCode)

        // Rolled back: the previously effective key + config are untouched.
        assertEquals("old-key", secret.key)
        assertEquals("http://old:11434/", public.stored!!.baseUrl)
        val state = repo.configState.value
        val valid = assertInstanceOf(ModelConfigState.Valid::class.java, state)
        assertEquals("old", valid.config.baseUrl.host)
        assertEquals(1L, valid.config.version)
    }

    @Test
    fun `invalid draft is rejected without touching the current config`() = runTest {
        val public = InMemoryPublicStore()
        val secret = InMemorySecretStore()
        val repo = buildRepo(public, secret, this)
        advanceUntilIdle()

        repo.save(ModelConfigDraft(baseUrl = "http://a:11434", apiKeyAction = ApiKeyAction.Replace("key")))
        val result = repo.save(ModelConfigDraft(baseUrl = "ftp://bad:21", apiKeyAction = ApiKeyAction.Replace("other")))

        val failure = assertInstanceOf(SaveResult.Failure::class.java, result)
        assertEquals(ModelConfigErrorCode.INVALID_URL, failure.errorCode)
        assertEquals("http://a:11434/", public.stored!!.baseUrl)
        assertEquals("key", secret.key)
        assertEquals(1L, public.stored!!.configVersion)
    }

    @Test
    fun `resetToDefault clears key and restores default baseUrl`() = runTest {
        val public = InMemoryPublicStore()
        val secret = InMemorySecretStore()
        val repo = buildRepo(public, secret, this, defaultUrl = "http://default-host:11434")
        advanceUntilIdle()

        repo.save(ModelConfigDraft(baseUrl = "http://custom:11434", apiKeyAction = ApiKeyAction.Replace("key")))
        val result = repo.resetToDefault()

        val success = assertInstanceOf(SaveResult.Success::class.java, result)
        assertEquals(2L, success.configVersion)
        assertEquals("http://default-host:11434/", public.stored!!.baseUrl)
        assertNull(secret.key)
        assertEquals(KeyStatus.NOT_SET, secret.status)
        assertEquals("default-host", (repo.configState.value as ModelConfigState.Valid).config.baseUrl.host)
    }

    @Test
    fun `undecryptable secret publishes Invalid and refuses requests`() = runTest {
        val public = InMemoryPublicStore(ModelPublicConfig(baseUrl = "http://x:11434", configVersion = 3L))
        val secret = InMemorySecretStore(key = "stale-ciphertext", status = KeyStatus.INVALID)
        val repo = buildRepo(public, secret, this)
        advanceUntilIdle()

        val state = repo.configState.value
        val invalid = assertInstanceOf(ModelConfigState.Invalid::class.java, state)
        assertEquals(ModelConfigErrorCode.DECRYPTION_FAILED, invalid.errorCode)
        val error = try {
            repo.loadSnapshot()
            null
        } catch (e: ModelConfigException) {
            e
        }
        assertEquals(ModelConfigErrorCode.DECRYPTION_FAILED, error?.errorCode)
    }

    @Test
    fun `incompatible schema version publishes Invalid`() = runTest {
        val public = InMemoryPublicStore(ModelPublicConfig(schemaVersion = 99, baseUrl = "http://x:11434", configVersion = 1L))
        val repo = buildRepo(public, InMemorySecretStore(), this)
        advanceUntilIdle()

        val state = repo.configState.value
        val invalid = assertInstanceOf(ModelConfigState.Invalid::class.java, state)
        assertEquals(ModelConfigErrorCode.VERSION_INCOMPATIBLE, invalid.errorCode)
    }

    @Test
    fun `concurrent saves are serialized and both versions applied`() = runTest {
        val public = InMemoryPublicStore()
        val secret = InMemorySecretStore()
        val repo = buildRepo(public, secret, this)
        advanceUntilIdle()

        val r1 = async { repo.save(ModelConfigDraft(baseUrl = "http://a:11434", apiKeyAction = ApiKeyAction.Replace("k1"))) }
        val r2 = async { repo.save(ModelConfigDraft(baseUrl = "http://b:11434", apiKeyAction = ApiKeyAction.Replace("k2"))) }
        val results = awaitAll(r1, r2)

        assertTrue(results.all { it is SaveResult.Success })
        assertEquals(2L, public.stored!!.configVersion)
        assertTrue(setOf("http://a:11434/", "http://b:11434/").contains(public.stored!!.baseUrl))
        assertTrue(setOf("k1", "k2").contains(secret.key))
    }

    @Test
    fun `v1 config is migrated to v2 as OLLAMA keeping baseUrl and default model`() = runTest {
        val public = InMemoryPublicStore(
            ModelPublicConfig(schemaVersion = 1, baseUrl = "http://192.168.2.170:11434", configVersion = 3L)
        )
        val repo = buildRepo(public, InMemorySecretStore(), this)
        advanceUntilIdle()

        val state = repo.configState.value
        val valid = assertInstanceOf(ModelConfigState.Valid::class.java, state)
        assertEquals(ModelProviderType.OLLAMA, valid.config.providerType)
        assertEquals("192.168.2.170", valid.config.baseUrl.host)
        assertEquals("qwen3.5:4b", valid.config.modelName)
        assertEquals(3L, valid.config.version)

        // Migration is persisted once: the stored config is now v2.
        assertEquals(ModelPublicConfig.CURRENT_SCHEMA_VERSION, public.stored!!.schemaVersion)
        assertEquals(ModelProviderType.OLLAMA, public.stored!!.providerType)
        assertEquals("qwen3.5:4b", public.stored!!.modelName)
    }

    @Test
    fun `save persists provider type model name and endpoint path for OpenAI compatible`() = runTest {
        val public = InMemoryPublicStore()
        val repo = buildRepo(public, InMemorySecretStore(), this)
        advanceUntilIdle()

        val result = repo.save(
            ModelConfigDraft(
                baseUrl = "http://localhost:8000",
                providerType = ModelProviderType.OPENAI_COMPATIBLE,
                modelName = "qwen2.5:7b",
                endpointPath = "/v1/chat/completions",
                apiKeyAction = ApiKeyAction.Replace("sk-1")
            )
        )
        assertInstanceOf(SaveResult.Success::class.java, result)

        assertEquals(ModelProviderType.OPENAI_COMPATIBLE, public.stored!!.providerType)
        assertEquals("qwen2.5:7b", public.stored!!.modelName)
        assertEquals("/v1/chat/completions", public.stored!!.endpointPath)
        assertEquals(ModelPublicConfig.CURRENT_SCHEMA_VERSION, public.stored!!.schemaVersion)

        val snap = repo.loadSnapshot()
        assertEquals(ModelProviderType.OPENAI_COMPATIBLE, snap.providerType)
        assertEquals("qwen2.5:7b", snap.modelName)
        assertEquals("/v1/chat/completions", snap.endpointPath)
    }

    @Test
    fun `openai compatible save without model name fails with IVAI-CONFIG-009`() = runTest {
        val public = InMemoryPublicStore()
        val repo = buildRepo(public, InMemorySecretStore(), this)
        advanceUntilIdle()

        val result = repo.save(
            ModelConfigDraft(
                baseUrl = "http://localhost:8000",
                providerType = ModelProviderType.OPENAI_COMPATIBLE,
                modelName = null
            )
        )
        val failure = assertInstanceOf(SaveResult.Failure::class.java, result)
        assertEquals(ModelConfigErrorCode.PROVIDER_MISMATCH, failure.errorCode)
        // Failed save must not corrupt the current (empty) config.
        assertNull(public.stored)
    }
}
