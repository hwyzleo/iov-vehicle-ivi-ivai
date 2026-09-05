package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Capability selection logic (IVI-IVAI-DSN-CR-006) with injectable platform
 * checks: permission → API31+ on-device → system service → unavailable.
 */
class AndroidSpeechCapabilityCheckerTest {

    private fun checker(
        permission: Boolean = true,
        onDevice: Boolean = false,
        system: Boolean = true
    ) = AndroidSpeechCapabilityChecker(
        hasPermission = { permission },
        onDeviceAvailable = { onDevice },
        systemAvailable = { system }
    )

    @Test
    fun `no permission is unavailable`() {
        assertEquals(SpeechCapability.UNAVAILABLE, checker(permission = false).capability())
    }

    @Test
    fun `on device available wins over system`() {
        assertEquals(
            SpeechCapability.ON_DEVICE,
            checker(permission = true, onDevice = true, system = true).capability()
        )
    }

    @Test
    fun `only system service available is system service`() {
        assertEquals(
            SpeechCapability.SYSTEM_SERVICE,
            checker(permission = true, onDevice = false, system = true).capability()
        )
    }

    @Test
    fun `nothing available is unavailable`() {
        assertEquals(
            SpeechCapability.UNAVAILABLE,
            checker(permission = true, onDevice = false, system = false).capability()
        )
    }

    @Test
    fun `on device available requires permission too`() {
        assertEquals(
            SpeechCapability.UNAVAILABLE,
            checker(permission = false, onDevice = true, system = true).capability()
        )
    }
}
