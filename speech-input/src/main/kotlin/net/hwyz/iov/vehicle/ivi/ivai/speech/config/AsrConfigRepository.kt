package net.hwyz.iov.vehicle.ivi.ivai.speech.config

import kotlinx.coroutines.flow.StateFlow
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.KeyStatus
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SaveResult
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ValidationResult

/**
 * Central ASR runtime configuration repository (IVI-IVAI-DSN-CR-006).
 *
 * `save()` must validate, encrypt and persist inside a single mutex region, and
 * only publish a new configVersion after every step succeeded — a failure at any
 * step must never corrupt the currently effective configuration.
 */
interface AsrConfigRepository {

    /** Published configuration state (Valid / Invalid) for UI and diagnostics. */
    val configState: StateFlow<AsrConfigState>

    /** Current effective snapshot; throws [AsrConfigException] when invalid. */
    suspend fun loadSnapshot(): AsrRuntimeConfig

    /** Saved key status — never the plaintext. */
    suspend fun keyStatus(): KeyStatus

    /** Validates the draft without persisting anything. */
    suspend fun validate(draft: AsrConfigDraft): ValidationResult

    /**
     * Runs a connectivity / capability test against the draft without saving.
     * Android providers need no network; HTTP/VENDOR perform a minimal health
     * check that never enters the Agent workflow or calls a Tool.
     */
    suspend fun testConnection(draft: AsrConfigDraft): ConnectionTestResult

    /** Validates + encrypts + persists the draft; does not overwrite on failure. */
    suspend fun save(draft: AsrConfigDraft): SaveResult

    /** Confirmed reset: restores the default provider and clears the saved key. */
    suspend fun resetToDefault(): SaveResult
}
