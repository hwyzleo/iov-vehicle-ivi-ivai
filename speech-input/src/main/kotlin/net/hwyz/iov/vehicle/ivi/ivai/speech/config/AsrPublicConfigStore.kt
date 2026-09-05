package net.hwyz.iov.vehicle.ivi.ivai.speech.config

/**
 * Persistence boundary for non-sensitive ASR public config. Implementations must
 * guarantee atomic writes so a crash never leaves a half-written config
 * (IVI-IVAI-DSN-CR-006, mirror of model-client PublicConfigStore).
 */
interface AsrPublicConfigStore {

    /** Returns the persisted config, or null when none was saved yet. */
    suspend fun load(): AsrPublicConfig?

    /** Atomically replaces the persisted config. */
    suspend fun write(config: AsrPublicConfig)

    /** Removes any persisted config (used by reset / migration failure). */
    suspend fun clear()
}
