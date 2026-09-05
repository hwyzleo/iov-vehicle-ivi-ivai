package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionConfig
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEngine
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEvent
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrFallbackPolicy
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrProviderType
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrPublicConfig
import net.hwyz.iov.vehicle.ivi.ivai.speech.config.AsrRuntimeConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Engine selection + fallback policy (IVI-IVAI-DSN-CR-006): on-device preferred
 * on API 31+, ON_DEVICE_TO_SYSTEM fallback only when the policy allows, remote
 * providers return null (future CR), and permission is a hard gate.
 */
class AndroidSpeechRecognitionEngineFactoryTest {

    private class RecordingEngine(val onDevice: Boolean) : SpeechRecognitionEngine {
        override suspend fun capability(): SpeechCapability =
            if (onDevice) SpeechCapability.ON_DEVICE else SpeechCapability.SYSTEM_SERVICE

        override fun events(): Flow<SpeechRecognitionEvent> = MutableSharedFlow()
        override fun start(config: SpeechRecognitionConfig): Result<Unit> = Result.success(Unit)
        override fun stop() = Unit
        override fun cancel() = Unit
        override fun release() = Unit
    }

    private class Harness(
        permission: Boolean = true,
        onDevice: Boolean = false,
        system: Boolean = true
    ) {
        val created = mutableListOf<Boolean>()
        private val checker = AndroidSpeechCapabilityChecker(
            hasPermission = { permission },
            onDeviceAvailable = { onDevice },
            systemAvailable = { system }
        )
        val factory = AndroidSpeechRecognitionEngineFactory(checker) { od, _ ->
            created += od
            RecordingEngine(od)
        }
    }

    private fun config(
        providerType: AsrProviderType,
        fallback: AsrFallbackPolicy = AsrFallbackPolicy.TEXT_ONLY
    ): AsrRuntimeConfig = AsrRuntimeConfig(
        public = AsrPublicConfig(providerType = providerType, fallbackPolicy = fallback),
        apiKey = null,
        version = 1L
    )

    @Test
    fun `on device provider picks on device engine when available`() {
        val h = Harness(onDevice = true)
        val engine = h.factory.create(config(AsrProviderType.ANDROID_ON_DEVICE))
        assertSame(RecordingEngine::class.java, engine!!::class.java)
        assertEquals(true, h.created.last())
    }

    @Test
    fun `on device unavailable with text only fallback returns null`() {
        val h = Harness(onDevice = false, system = true)
        assertNull(h.factory.create(config(AsrProviderType.ANDROID_ON_DEVICE)))
    }

    @Test
    fun `on device unavailable with on-device-to-system fallback uses system engine`() {
        val h = Harness(onDevice = false, system = true)
        val engine = h.factory.create(
            config(AsrProviderType.ANDROID_ON_DEVICE, AsrFallbackPolicy.ON_DEVICE_TO_SYSTEM)
        )
        assertEquals(false, h.created.last())
        assertSame(RecordingEngine::class.java, engine!!::class.java)
    }

    @Test
    fun `on-device-to-system fallback is refused when no system service`() {
        val h = Harness(onDevice = false, system = false)
        assertNull(
            h.factory.create(
                config(AsrProviderType.ANDROID_ON_DEVICE, AsrFallbackPolicy.ON_DEVICE_TO_SYSTEM)
            )
        )
    }

    @Test
    fun `system provider picks system engine when available`() {
        val h = Harness(system = true)
        val engine = h.factory.create(config(AsrProviderType.ANDROID_SYSTEM))
        assertEquals(false, h.created.last())
        assertSame(RecordingEngine::class.java, engine!!::class.java)
    }

    @Test
    fun `system provider unavailable returns null`() {
        val h = Harness(system = false)
        assertNull(h.factory.create(config(AsrProviderType.ANDROID_SYSTEM)))
    }

    @Test
    fun `remote providers are not yet implemented`() {
        val h = Harness()
        assertNull(h.factory.create(config(AsrProviderType.HTTP_COMPATIBLE)))
        assertNull(h.factory.create(config(AsrProviderType.VENDOR)))
    }

    @Test
    fun `missing permission refuses any engine`() {
        val h = Harness(permission = false, onDevice = true, system = true)
        assertNull(h.factory.create(config(AsrProviderType.ANDROID_ON_DEVICE)))
        assertNull(h.factory.create(config(AsrProviderType.ANDROID_SYSTEM)))
    }
}
