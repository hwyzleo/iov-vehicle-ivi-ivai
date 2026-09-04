package net.hwyz.iov.vehicle.ivi.ivai.model.provider

import net.hwyz.iov.vehicle.ivi.ivai.model.ModelClientException
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelErrorKind
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProviderType
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigSnapshotProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelRuntimeConfig
import okhttp3.OkHttpClient

/**
 * Builds the concrete [ModelProvider] for a runtime config snapshot
 * (IVI-IVAI-DSN-CR-004). The snapshot's providerType decides the backend; an
 * unsupported type is refused with IVAI-MODEL-004 instead of failing at request
 * time with an obscure error.
 */
class ModelProviderFactory(
    private val snapshotProvider: ModelConfigSnapshotProvider,
    private val client: OkHttpClient
) {

    fun create(config: ModelRuntimeConfig): ModelProvider = when (config.providerType) {
        ModelProviderType.OLLAMA -> OllamaModelProvider(
            snapshotProvider = snapshotProvider,
            client = client
        )
        ModelProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleModelProvider(
            snapshotProvider = snapshotProvider,
            client = client
        )
        else -> throw ModelClientException(
            kind = ModelErrorKind.PROVIDER_UNSUPPORTED,
            message = "不支持的 Provider 类型：${config.providerType}"
        )
    }
}
