package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import net.hwyz.iov.vehicle.ivi.ivai.speech.api.AsrErrorCode
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * HTTP_COMPATIBLE response parsing (IVI-IVAI-DSN-CR-007): 2xx text extraction
 * with trimming, and the full failure map — 401/403 → IVAI-ASR-011, 5xx →
 * IVAI-ASR-013, invalid JSON / missing text → IVAI-ASR-012, blank text →
 * IVAI-ASR-010.
 */
class HttpAsrResponseParserTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val parser = HttpAsrResponseParser()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun parse(status: Int, body: String? = null): HttpAsrParseResult {
        val responseBuilder = MockResponse().setResponseCode(status)
        if (body != null) responseBuilder.setBody(body)
        server.enqueue(responseBuilder)
        val response = client.newCall(Request.Builder().url(server.url("/transcriptions")).build())
            .execute()
        return response.use { parser.parse(it) }
    }

    @Test
    fun `2xx with text yields trimmed success`() {
        val result = parse(200, """{"text":"  打开空调  "}""")
        assertInstanceOf(HttpAsrParseResult.Success::class.java, result)
        assertEquals("打开空调", (result as HttpAsrParseResult.Success).text)
        assertEquals(200, result.httpStatus)
    }

    @Test
    fun `2xx with extra unknown fields is tolerated`() {
        val result = parse(200, """{"text":"打开空调","duration":1.2,"language":"zh"}""")
        assertEquals("打开空调", (result as HttpAsrParseResult.Success).text)
    }

    @Test
    fun `401 maps to remote unauthorized`() {
        val result = parse(401, """{"error":{"message":"invalid api key"}}""")
        assertFailure(result, AsrErrorCode.REMOTE_UNAUTHORIZED, 401)
    }

    @Test
    fun `403 maps to remote unauthorized`() {
        val result = parse(403, """{"error":{"message":"forbidden"}}""")
        assertFailure(result, AsrErrorCode.REMOTE_UNAUTHORIZED, 403)
    }

    @Test
    fun `5xx maps to remote service error`() {
        val result = parse(500, """{"error":{"message":"boom"}}""")
        assertFailure(result, AsrErrorCode.REMOTE_SERVICE_ERROR, 500)
        val gateway = parse(502, "")
        assertFailure(gateway, AsrErrorCode.REMOTE_SERVICE_ERROR, 502)
    }

    @Test
    fun `other 4xx maps to a network category with sanitized status`() {
        val result = parse(400, """{"error":{"message":"bad request"}}""")
        assertFailure(result, AsrErrorCode.NETWORK_ERROR, 400)
        val tooMany = parse(429, "")
        assertFailure(tooMany, AsrErrorCode.NETWORK_ERROR, 429)
    }

    @Test
    fun `illegal json on 2xx maps to invalid response`() {
        val result = parse(200, "not json at all")
        assertFailure(result, AsrErrorCode.REMOTE_INVALID_RESPONSE, 200)
    }

    @Test
    fun `missing text on 2xx maps to invalid response`() {
        val result = parse(200, """{"transcript":"打开空调"}""")
        assertFailure(result, AsrErrorCode.REMOTE_INVALID_RESPONSE, 200)
    }

    @Test
    fun `blank text on 2xx maps to empty result`() {
        val result = parse(200, """{"text":"   "}""")
        assertFailure(result, AsrErrorCode.EMPTY_RESULT, 200)
        val empty = parse(200, """{"text":""}""")
        assertFailure(empty, AsrErrorCode.EMPTY_RESULT, 200)
    }

    @Test
    fun `errors carry the http-compatible engine type and never on-device`() {
        val result = parse(500, "")
        val failure = result as HttpAsrParseResult.Failure
        assertEquals("http-compatible", failure.error.engineType)
        assertEquals(false, failure.error.onDevice)
    }

    private fun assertFailure(result: HttpAsrParseResult, code: String, status: Int) {
        assertInstanceOf(HttpAsrParseResult.Failure::class.java, result)
        val failure = result as HttpAsrParseResult.Failure
        assertEquals(code, failure.error.code)
        assertEquals(status, failure.httpStatus)
    }
}
