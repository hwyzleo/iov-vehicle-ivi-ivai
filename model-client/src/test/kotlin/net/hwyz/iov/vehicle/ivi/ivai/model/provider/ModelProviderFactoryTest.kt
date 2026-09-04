package net.hwyz.iov.vehicle.ivi.ivai.model.provider

import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProviderType
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelConfigSnapshotProvider
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ModelRuntimeConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * [ModelProviderFactory] routing (IVI-IVAI-DSN-CR-004): OLLAMA → Ollama,
 * OPENAI_COMPATIBLE → OpenAI compatible. Unsupported types are refused with
 * IVAI-MODEL-004 at the factory boundary (defensive `else` branch).
 */
class ModelProviderFactoryTest {

    private val snapshotProvider = ModelConfigSnapshotProvider {
        ModelRuntimeConfig(
            baseUrl = "http://localhost:11434".toHttpUrl(),
            apiKey = null,
            version = 0L
        )
    }

    private fun config(providerType: ModelProviderType) = ModelRuntimeConfig(
        baseUrl = "http://localhost:11434".toHttpUrl(),
        providerType = providerType,
        modelName = "qwen3.5:4b",
        apiKey = null,
        version = 0L
    )

    @Test
    fun `routes OLLAMA to OllamaModelProvider`() {
        val factory = ModelProviderFactory(snapshotProvider, OkHttpClient())
        assertInstanceOf(OllamaModelProvider::class.java, factory.create(config(ModelProviderType.OLLAMA)))
    }

    @Test
    fun `routes OPENAI_COMPATIBLE to OpenAiCompatibleModelProvider`() {
        val factory = ModelProviderFactory(snapshotProvider, OkHttpClient())
        assertInstanceOf(
            OpenAiCompatibleModelProvider::class.java,
            factory.create(config(ModelProviderType.OPENAI_COMPATIBLE))
        )
    }
}
