package net.hwyz.iov.vehicle.ivi.ivai.model.config

/**
 * Persistence boundary for non-sensitive public config. Implementations must
 * guarantee atomic writes (single preference / temp-file rename) so a crash
 * never leaves a half-written config (IVI-IVAI-DSN-CR-003).
 */
interface PublicConfigStore {

    /** Returns the persisted config, or null when none was saved yet. */
    suspend fun load(): ModelPublicConfig?

    /** Atomically replaces the persisted config. */
    suspend fun write(config: ModelPublicConfig)

    /** Removes any persisted config (used by reset / migration failure). */
    suspend fun clear()
}

/**
 * Persistence boundary for the encrypted API key. Implementations must never
 * store or expose the plaintext; [read] returns the decrypted value only in
 * memory, for immediate use by the caller.
 */
interface SecretStore {

    /** Current key status: SET / NOT_SET / INVALID (decrypt failed or key lost). */
    suspend fun status(): KeyStatus

    /** Encrypts and persists [key]. Throws when encryption or persistence fails. */
    suspend fun write(key: String)

    /** Returns the decrypted key, or null when not set / not decryptable. */
    suspend fun read(): String?

    /** Removes the persisted ciphertext. */
    suspend fun clear()
}
