package net.hwyz.iov.vehicle.ivi.ivai.model.config

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModelPublicConfigSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round trips public config`() {
        val config = ModelPublicConfig(
            schemaVersion = 1,
            baseUrl = "http://192.168.2.170:11434/",
            updatedAt = 1_700_000_000_000L,
            configVersion = 7L
        )
        val encoded = json.encodeToString(ModelPublicConfig.serializer(), config)
        val decoded = json.decodeFromString(ModelPublicConfig.serializer(), encoded)
        assertEquals(config, decoded)
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
