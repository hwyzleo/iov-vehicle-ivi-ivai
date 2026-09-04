package net.hwyz.iov.vehicle.ivi.ivai.model.config

/** Configuration error carrying an IVAI-CONFIG-* code. */
class ModelConfigException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
