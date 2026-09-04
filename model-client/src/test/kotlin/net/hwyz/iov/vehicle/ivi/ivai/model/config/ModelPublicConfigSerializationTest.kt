package net.hwyz.iov.vehicle.ivi.ivai.model.config

import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.ivai.model.ModelProviderType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ModelPublicConfigSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round trips v2 public config`() {
        val config = ModelPublicConfig(
            schemaVersion = 2,
            baseUrl = "http://192.168.2.170:11434/",
            providerType = ModelProviderType.OPENAI_COMPATIBLE,
            endpointPath = "/v1/chat/completions",
            modelName = "qwen3.5:4b",
            updatedAt = 1_700_000_000_000L,
            configVersion = 7L
        )
        val encoded = json.encodeToString(ModelPublicConfig.serializer(), config)
        val decoded = json.decodeFromString(ModelPublicConfig.serializer(), encoded)
        assertEquals(config, decoded)
        assertEquals(ModelProviderType.OPENAI_COMPATIBLE, decoded.providerType)
        assertEquals("/v1/chat/completions", decoded.endpointPath)
        assertEquals("qwen3.5:4b", decoded.modelName)
    }

    @Test
    fun `v2 defaults to OLLAMA when provider fields omitted`() {
        val decoded = json.decodeFromString(
            ModelPublicConfig.serializer(),
            """{"schemaVersion":2,"baseUrl":"http://x:11434"}"""
        )
        assertEquals("http://x:11434", decoded.baseUrl)
        assertEquals(ModelProviderType.OLLAMA, decoded.providerType)
        assertNull(decoded.endpointPath)
        assertNull(decoded.modelName)
    }

    @Test
    fun `unknown fields are tolerated`() {
        val decoded = json.decodeFromString(
            ModelPublicConfig.serializer(),
            """{"schemaVersion":1,"baseUrl":"http://x:11434","futureField":"ignored"}"""
        )
        assertEquals("http://x:11434", decoded.baseUrl)
        assertEquals(1, decoded.schemaVersion)
    }
}
