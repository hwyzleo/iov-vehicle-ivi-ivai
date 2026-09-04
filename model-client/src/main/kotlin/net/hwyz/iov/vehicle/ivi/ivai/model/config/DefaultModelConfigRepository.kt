package net.hwyz.iov.vehicle.ivi.ivai.model.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Default repository implementation (IVI-IVAI-DSN-CR-003). Pure JVM: all
 * Android-specific persistence is injected through [PublicConfigStore] /
 * [SecretStore], so the whole save / load / rollback logic is unit-testable.
 *
 * Save ordering (design):
 *  1. validate the draft
 *  2. encrypt + persist the new secret first (abort on failure — public config untouched)
 *  3. persist the public config with the bumped configVersion
 *  4. on public-write failure, roll the secret back so the previously effective
 *     configuration is never left in a half-migrated state
 *  5. only then publish the new Valid state
 */
class DefaultModelConfigRepository(
    private val publicStore: PublicConfigStore,
    private val secretStore: SecretStore,
    private val defaultBaseUrl: String,
    private val validator: ModelConfigValidator = ModelConfigValidator(),
    private val connectionTester: ModelConnectionTester = ModelConnectionTester(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : ModelConfigRepository {

    private val mutex = Mutex()
    private val _configState = MutableStateFlow<ModelConfigState>(ModelConfigState.Loading)
    override val configState: StateFlow<ModelConfigState> = _configState.asStateFlow()

    init {
        scope.launch { reload() }
    }

    override suspend fun loadSnapshot(): ModelRuntimeConfig {
        val current = _configState.value
        if (current is ModelConfigState.Valid) return current.config
        // Initial load may still be in flight or stale — read the stores directly.
        return loadFromStores()
    }

    override suspend fun keyStatus(): KeyStatus = secretStore.status()

    override suspend fun validate(draft: ModelConfigDraft): ValidationResult =
        validator.validate(draft)

    override suspend fun testConnection(draft: ModelConfigDraft): ConnectionTestResult =
        connectionTester.test(draft)

    override suspend fun save(draft: ModelConfigDraft): SaveResult = mutex.withLock {
        val validation = validator.validate(draft)
        if (validation is ValidationResult.Invalid) {
            val first = validation.errors.first()
            return SaveResult.Failure(first.code, first.message)
        }

        val normalizedUrl = validator.normalize(draft.baseUrl)
        val previousPublic = runCatching { publicStore.load() }.getOrNull()
        val newVersion = (previousPublic?.configVersion ?: 0L) + 1L

        // 1. Secret first — a failure here aborts before touching public config.
        val previousKey = runCatching { secretStore.read() }.getOrNull()
        try {
            when (val action = draft.apiKeyAction) {
                ApiKeyAction.Keep -> Unit
                is ApiKeyAction.Replace -> secretStore.write(action.value)
                ApiKeyAction.Clear -> secretStore.clear()
            }
        } catch (e: ModelConfigException) {
            return SaveResult.Failure(e.errorCode, e.message ?: "密钥保存失败")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SaveResult.Failure(ModelConfigErrorCode.ENCRYPTION_FAILED, "密钥加密或保存失败")
        }

        // 2. Public config + bumped version — failure rolls the secret back.
        val newPublic = ModelPublicConfig(
            schemaVersion = ModelPublicConfig.CURRENT_SCHEMA_VERSION,
            baseUrl = normalizedUrl,
            updatedAt = System.currentTimeMillis(),
            configVersion = newVersion
        )
        try {
            publicStore.write(newPublic)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rollbackSecret(previousKey, draft.apiKeyAction)
            return SaveResult.Failure(ModelConfigErrorCode.PERSISTENCE_FAILED, "配置持久化失败")
        }

        _configState.value = ModelConfigState.Valid(buildSnapshot(newPublic))
        SaveResult.Success(newVersion)
    }

    override suspend fun resetToDefault(): SaveResult = mutex.withLock {
        val previousPublic = runCatching { publicStore.load() }.getOrNull()
        val newVersion = (previousPublic?.configVersion ?: 0L) + 1L

        try {
            secretStore.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SaveResult.Failure(ModelConfigErrorCode.ENCRYPTION_FAILED, "清除密钥失败")
        }

        val normalizedDefault = validator.normalize(defaultBaseUrl)
        if (normalizedDefault.toHttpUrlOrNull() == null) {
            return SaveResult.Failure(ModelConfigErrorCode.INVALID_URL, "默认地址格式非法")
        }
        val newPublic = ModelPublicConfig(
            schemaVersion = ModelPublicConfig.CURRENT_SCHEMA_VERSION,
            baseUrl = normalizedDefault,
            updatedAt = System.currentTimeMillis(),
            configVersion = newVersion
        )
        try {
            publicStore.write(newPublic)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SaveResult.Failure(ModelConfigErrorCode.PERSISTENCE_FAILED, "配置持久化失败")
        }

        _configState.value = ModelConfigState.Valid(buildSnapshot(newPublic))
        SaveResult.Success(newVersion)
    }

    private suspend fun reload() {
        _configState.value = try {
            ModelConfigState.Valid(loadFromStores())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelConfigException) {
            ModelConfigState.Invalid(e.message ?: "配置无效", e.errorCode)
        } catch (e: Exception) {
            ModelConfigState.Invalid(
                "配置读取失败: ${e.message}",
                ModelConfigErrorCode.PERSISTENCE_FAILED
            )
        }
    }

    private suspend fun loadFromStores(): ModelRuntimeConfig {
        val public = runCatching { publicStore.load() }.getOrElse { e ->
            throw ModelConfigException(
                ModelConfigErrorCode.PERSISTENCE_FAILED,
                "配置读取失败: ${e.message}",
                e
            )
        } ?: throw ModelConfigException(
            ModelConfigErrorCode.MISSING_REQUIRED,
            "尚未配置模型地址，请先完成配置"
        )

        if (public.schemaVersion > ModelPublicConfig.CURRENT_SCHEMA_VERSION) {
            throw ModelConfigException(
                ModelConfigErrorCode.VERSION_INCOMPATIBLE,
                "配置版本 ${public.schemaVersion} 不兼容，请重新配置或恢复默认"
            )
        }
        // schemaVersion < CURRENT: migration hooks would run here; v1 is the only version today.

        return buildSnapshot(public)
    }

    private suspend fun buildSnapshot(public: ModelPublicConfig): ModelRuntimeConfig {
        val baseUrl = public.baseUrl.toHttpUrlOrNull()
            ?: throw ModelConfigException(ModelConfigErrorCode.INVALID_URL, "持久化地址非法")

        val apiKey = when (secretStore.status()) {
            KeyStatus.NOT_SET -> null
            KeyStatus.SET -> secretStore.read()?.let { SecretValue.of(it) }
                ?: throw ModelConfigException(
                    ModelConfigErrorCode.DECRYPTION_FAILED,
                    "密钥解密失败"
                )
            KeyStatus.INVALID -> throw ModelConfigException(
                ModelConfigErrorCode.DECRYPTION_FAILED,
                "密钥解密失败或 Keystore 失效，请重新配置"
            )
        }
        return ModelRuntimeConfig(baseUrl = baseUrl, apiKey = apiKey, version = public.configVersion)
    }

    /** Best-effort rollback of the secret after a failed public-config write. */
    private suspend fun rollbackSecret(previousKey: String?, action: ApiKeyAction) {
        if (action == ApiKeyAction.Keep) return // secret was never touched
        try {
            if (previousKey == null) secretStore.clear() else secretStore.write(previousKey)
        } catch (_: Exception) {
            // Best effort only — the next valid save overwrites the secret anyway.
        }
    }
}
