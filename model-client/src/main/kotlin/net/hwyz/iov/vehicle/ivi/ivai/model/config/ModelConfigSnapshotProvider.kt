package net.hwyz.iov.vehicle.ivi.ivai.model.config

/**
 * Supplies the immutable runtime config snapshot a
 * [net.hwyz.iov.vehicle.ivi.ivai.model.ModelProvider] must capture at the start
 * of each request, so in-flight requests keep their config and new requests use
 * the latest one (IVI-IVAI-DSN-CR-003 / IVAI-REQ-025).
 */
fun interface ModelConfigSnapshotProvider {

    /**
     * Returns the current effective snapshot.
     * @throws ModelConfigException when the persisted config is missing, corrupt,
     *   incompatible or the secret cannot be decrypted — callers must refuse the
     *   request in that case.
     */
    suspend fun loadSnapshot(): ModelRuntimeConfig
}
