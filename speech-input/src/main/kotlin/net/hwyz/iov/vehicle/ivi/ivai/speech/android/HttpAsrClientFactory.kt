package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Builds the OkHttp client for HTTP_COMPATIBLE ASR uploads (IVI-IVAI-DSN-CR-007).
 *
 * - [connectTimeoutMs] maps to OkHttp's connect timeout (AsrPublicConfig).
 * - [readTimeoutMs] is set to the recognition timeout so OkHttp never fires
 *   before the engine's own total deadline; the engine still enforces the full
 *   recognitionTimeoutMs (upload start → full response read) via withTimeout
 *   and cancels the in-flight call.
 *
 * The client is shared per engine instance (OkHttp clients are thread-safe and
 * pool connections); only individual [okhttp3.Call]s are cancelled on cancel().
 */
class HttpAsrClientFactory(
    private val connectTimeoutMs: Long,
    private val readTimeoutMs: Long
) {

    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()
}
