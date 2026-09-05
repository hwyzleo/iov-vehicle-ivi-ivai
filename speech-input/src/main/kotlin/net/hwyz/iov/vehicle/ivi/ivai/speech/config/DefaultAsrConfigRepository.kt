package net.hwyz.iov.vehicle.ivi.ivai.speech.config

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
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretValue
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult

/**
 * Default repository implementation (IVI-IVAI-DSN-CR-006), mirroring
 * model-client DefaultModelConfigRepository. Pure JVM: all Android-specific
 * persistence is injected through [AsrPublicConfigStore] / [AsrSecretStore],
 * so the whole save / load / rollback logic is unit-testable.
 *
 * Save ordering:
 *  1. validate the draft
 *  2. encrypt + persist the new secret first (abort on failure — public untouched)
 *  3. persist the public config with the bumped configVersion
 *  4. on public-write failure, roll the secret back so the previously effective
 *     configuration is never left half-migrated
 *  5. only then publish the new Valid state
 */
class DefaultAsrConfigRepository(
    private val publicStore: AsrPublicConfigStore,
    private val secretStore: AsrSecretStore,
    private val defaultConfig: AsrPublicConfig = AsrPublicConfig(),
    private val validator: AsrConfigValidator = AsrConfigValidator(),
    private val connectionTester: AsrConnectionTester = AsrConnectionTester(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : AsrConfigRepository {

    private val mutex = Mutex()
    private val _configState = MutableStateFlow<AsrConfigState>(AsrConfigState.Loading)
    override val configState: StateFlow<AsrConfigState> = _configState.asStateFlow()

    init {
        scope.launch { reload() }
    }

    override suspend fun loadSnapshot(): AsrRuntimeConfig {
        val current = _configState.value
        if (current is AsrConfigState.Valid) return current.config
        // Initial load may still be in flight or stale — read the stores directly.
        return loadFromStores()
    }

    override suspend fun keyStatus(): KeyStatus = secretStore.status()

    override suspend fun validate(draft: AsrConfigDraft): ValidationResult =
        validator.validate(draft)

    override suspend fun testConnection(draft: AsrConfigDraft): net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult =
        connectionTester.test(draft)

    override suspend fun save(draft: AsrConfigDraft): SaveResult = mutex.withLock {
        val validation = validator.validate(draft)
        if (validation is ValidationResult.Invalid) {
            val first = validation.errors.first()
            return SaveResult.Failure(first.code, first.message)
        }

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SaveResult.Failure(AsrConfigErrorCode.KEY_ERROR, "密钥加密或保存失败")
        }

        // 2. Public config + bumped version — failure rolls the secret back.
        val newPublic = AsrPublicConfig(
            schemaVersion = AsrPublicConfig.CURRENT_SCHEMA_VERSION,
            providerType = draft.providerType,
            baseUrl = normalizeRemoteUrl(draft),
            modelName = draft.modelName.trim().takeIf { it.isNotEmpty() },
            languageTag = draft.languageTag.trim().ifEmpty { AsrPublicConfig.DEFAULT_LANGUAGE_TAG },
            preferOffline = draft.preferOffline,
            connectTimeoutMs = draft.connectTimeoutMs,
            recognitionTimeoutMs = draft.recognitionTimeoutMs,
            fallbackPolicy = draft.fallbackPolicy,
            configVersion = newVersion
        )
        try {
            publicStore.write(newPublic)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rollbackSecret(previousKey, draft.apiKeyAction)
            return SaveResult.Failure(AsrConfigErrorCode.PERSISTENCE_FAILED, "配置持久化失败")
        }

        _configState.value = AsrConfigState.Valid(buildSnapshot(newPublic))
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
            return SaveResult.Failure(AsrConfigErrorCode.KEY_ERROR, "清除密钥失败")
        }

        val newPublic = defaultConfig.copy(configVersion = newVersion)
        try {
            publicStore.write(newPublic)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SaveResult.Failure(AsrConfigErrorCode.PERSISTENCE_FAILED, "配置持久化失败")
        }

        _configState.value = AsrConfigState.Valid(buildSnapshot(newPublic))
        SaveResult.Success(newVersion)
    }

    private fun normalizeRemoteUrl(draft: AsrConfigDraft): String? =
        if (draft.providerType == AsrProviderType.HTTP_COMPATIBLE ||
            draft.providerType == AsrProviderType.VENDOR
        ) {
            validator.normalizeBaseUrl(draft.baseUrl).takeIf { it.isNotEmpty() }
        } else {
            null
        }

    private suspend fun reload() {
        _configState.value = try {
            AsrConfigState.Valid(loadFromStores())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AsrConfigException) {
            AsrConfigState.Invalid(e.message ?: "配置无效", e.errorCode)
        } catch (e: Exception) {
            AsrConfigState.Invalid(
                "配置读取失败: ${e.message}",
                AsrConfigErrorCode.PERSISTENCE_FAILED
            )
        }
    }

    private suspend fun loadFromStores(): AsrRuntimeConfig {
        val public = runCatching { publicStore.load() }.getOrElse { e ->
            throw AsrConfigException(
                AsrConfigErrorCode.PERSISTENCE_FAILED,
                "配置读取失败: ${e.message}",
                e
            )
        } ?: throw AsrConfigException(
            AsrConfigErrorCode.PERSISTENCE_FAILED,
            "尚未配置 ASR，请先完成配置"
        )

        if (public.schemaVersion > AsrPublicConfig.CURRENT_SCHEMA_VERSION) {
            throw AsrConfigException(
                AsrConfigErrorCode.PERSISTENCE_FAILED,
                "配置版本 ${public.schemaVersion} 不兼容，请重新配置或恢复默认"
            )
        }
        return buildSnapshot(public)
    }

    private suspend fun buildSnapshot(public: AsrPublicConfig): AsrRuntimeConfig {
        val apiKey = when (secretStore.status()) {
            KeyStatus.NOT_SET -> null
            KeyStatus.SET -> secretStore.read()?.let { SecretValue.of(it) }
                ?: throw AsrConfigException(AsrConfigErrorCode.KEY_ERROR, "密钥解密失败")
            KeyStatus.INVALID -> throw AsrConfigException(
                AsrConfigErrorCode.KEY_ERROR,
                "密钥解密失败或 Keystore 失效，请重新配置"
            )
        }
        return AsrRuntimeConfig(
            public = public,
            apiKey = apiKey,
            version = public.configVersion
        )
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
