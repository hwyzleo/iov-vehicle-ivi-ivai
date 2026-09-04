package net.hwyz.iov.vehicle.ivi.ivai.model

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Per-request OkHttp [EventListener] collecting network sub-metrics inside a
 * single model call (IVI-IVAI-DSN-CR-004). Durations are computed from a
 * monotonic clock (System.nanoTime).
 *
 * Semantics:
 *  - connection reuse yields no DNS / connect / TLS events → fields stay null,
 *    never 0 ([connectionReused] becomes true)
 *  - timeToFirstByte = response headers start − request body/headers end
 *  - the collected [HttpNetworkMetrics] is diagnostic-only: never summed with
 *    the top-level model-call total again
 *
 * Create a fresh instance per call and attach it to the OkHttpClient via
 * `baseClient.newBuilder().eventListener(listener).build()`.
 */
class NetworkMetricsEventListener : EventListener() {

    private val dnsStartNs = AtomicLong(0L)
    private val dnsEndNs = AtomicLong(0L)
    private val connectStartNs = AtomicLong(0L)
    private val secureConnectStartNs = AtomicLong(0L)
    private val secureConnectEndNs = AtomicLong(0L)
    private val connectEndNs = AtomicLong(0L)
    private val requestHeadersStartNs = AtomicLong(0L)
    private val requestHeadersEndNs = AtomicLong(0L)
    private val requestBodyStartNs = AtomicLong(0L)
    private val requestBodyEndNs = AtomicLong(0L)
    private val responseHeadersStartNs = AtomicLong(0L)
    private val responseBodyStartNs = AtomicLong(0L)
    private val responseBodyEndNs = AtomicLong(0L)
    private val hasRequestBody = AtomicBoolean(false)
    private val connectStarted = AtomicBoolean(false)

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartNs.set(nowNs())
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsEndNs.set(nowNs())
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStarted.set(true)
        connectStartNs.set(nowNs())
    }

    override fun secureConnectStart(call: Call) {
        secureConnectStartNs.set(nowNs())
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        secureConnectEndNs.set(nowNs())
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) {
        connectEndNs.set(nowNs())
    }

    override fun requestHeadersStart(call: Call) {
        requestHeadersStartNs.set(nowNs())
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        requestHeadersEndNs.set(nowNs())
    }

    override fun requestBodyStart(call: Call) {
        hasRequestBody.set(true)
        requestBodyStartNs.set(nowNs())
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        requestBodyEndNs.set(nowNs())
    }

    override fun responseHeadersStart(call: Call) {
        responseHeadersStartNs.set(nowNs())
    }

    override fun responseBodyStart(call: Call) {
        responseBodyStartNs.set(nowNs())
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        responseBodyEndNs.set(nowNs())
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) {
        // Leave connect segment null on failure; the error path reports the kind.
    }

    /** Snapshots the collected durations. Missing segments stay null. */
    fun snapshot(): HttpNetworkMetrics = HttpNetworkMetrics(
        dnsMs = millis(dnsStartNs.get(), dnsEndNs.get()),
        connectMs = rawConnectMs(),
        tlsMs = millis(secureConnectStartNs.get(), secureConnectEndNs.get()),
        requestWriteMs = requestWriteMs(),
        timeToFirstByteMs = millis(requestWriteEndNs(), responseHeadersStartNs.get()),
        responseReadMs = millis(responseBodyStartNs.get(), responseBodyEndNs.get()),
        connectionReused = !connectStarted.get()
    )

    private fun rawConnectMs(): Long? = when {
        secureConnectStartNs.get() > 0L && connectStartNs.get() > 0L ->
            millis(connectStartNs.get(), secureConnectStartNs.get())
        connectStartNs.get() > 0L && connectEndNs.get() > 0L ->
            millis(connectStartNs.get(), connectEndNs.get())
        else -> null
    }

    private fun requestWriteMs(): Long? =
        if (hasRequestBody.get()) {
            millis(requestBodyStartNs.get(), requestBodyEndNs.get())
        } else {
            millis(requestHeadersStartNs.get(), requestHeadersEndNs.get())
        }

    private fun requestWriteEndNs(): Long =
        if (hasRequestBody.get()) requestBodyEndNs.get() else requestHeadersEndNs.get()

    private fun millis(startNs: Long, endNs: Long): Long? {
        if (startNs <= 0L || endNs <= 0L || endNs < startNs) return null
        return (endNs - startNs) / NANOS_PER_MILLI
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        fun nowNs(): Long = System.nanoTime()
    }
}
