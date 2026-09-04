package net.hwyz.iov.vehicle.ivi.ivai.service.config

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Debug logging interceptor that redacts sensitive request headers
 * (Authorization, api-key, x-api-key) and never logs query parameters
 * (IVI-IVAI-DSN-CR-003 security section). Logs only sanitized scheme/host/port/path,
 * the header names with masked values, status and duration.
 */
class MaskingLoggingInterceptor(
    private val tag: String = "IVI-IVAI-HTTP"
) : Interceptor {

    private val sensitiveHeaders = setOf(
        "Authorization",
        "api-key",
        "x-api-key",
        "x-goog-api-key"
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val sb = StringBuilder()
        sb.append("--> ").append(request.method).append(' ')
            .append(request.url.scheme).append("://")
            .append(request.url.host).append(':')
            .append(request.url.port)
            .append(request.url.encodedPath)
        for (name in request.headers.names()) {
            val value = if (name in sensitiveHeaders) {
                "***"
            } else {
                request.headers[name].orEmpty()
            }
            sb.append('\n').append(name).append(": ").append(value)
        }

        val start = System.nanoTime()
        try {
            val response = chain.proceed(request)
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            Log.d(tag, "$sb\n<-- ${response.code} (${ms}ms)")
            return response
        } catch (e: IOException) {
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            Log.d(tag, "$sb\n<-- FAILED (${ms}ms): ${e.javaClass.simpleName}")
            throw e
        }
    }
}
