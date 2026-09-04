package net.hwyz.iov.vehicle.ivi.ivai.model.config

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Connection test behavior (IVI-IVAI-DSN-CR-003): success / unauthorized /
 * timeout / network error / invalid input. A successful test must never save.
 */
class ModelConnectionTesterTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun tester(connect: Long = 3_000, read: Long = 10_000, total: Long = 10_000) =
        ModelConnectionTester(connectTimeoutMs = connect, readTimeoutMs = read, totalTimeoutMs = total)

    @Test
    fun `2xx response is a success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))
        val result = tester().test(ModelConfigDraft(server.url("/").toString()))
        assertEquals(ConnectionTestResult.Success, result)
        val recorded = server.takeRequest()
        assertEquals("/api/tags", recorded.path)
    }

    @Test
    fun `401 and 403 are unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403))
        val tester = tester()
        assertEquals(ConnectionTestResult.Unauthorized, tester.test(ModelConfigDraft(server.url("/").toString())))
        assertEquals(ConnectionTestResult.Unauthorized, tester.test(ModelConfigDraft(server.url("/").toString())))
    }

    @Test
    fun `non-2xx is invalid response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = tester().test(ModelConfigDraft(server.url("/").toString()))
        val invalid = assertInstanceOf(ConnectionTestResult.InvalidResponse::class.java, result)
        assertEquals("HTTP 500", invalid.detail)
    }

    @Test
    fun `server that never replies beyond total timeout is a timeout`() = runBlocking {
        // No response enqueued → connection accepted but the server never replies,
        // so the coroutine total timeout fires deterministically.
        val result = tester(connect = 200, read = 10_000, total = 300)
            .test(ModelConfigDraft(server.url("/").toString()))
        assertEquals(ConnectionTestResult.Timeout, result)
    }

    @Test
    fun `connection refused is a network error`() = runBlocking {
        val url = server.url("/").toString()
        server.shutdown()
        val result = tester(connect = 500, read = 1_000, total = 1_000).test(ModelConfigDraft(url))
        assertInstanceOf(ConnectionTestResult.NetworkError::class.java, result)
    }

    @Test
    fun `invalid url draft is an invalid response and does not hit the network`() = runBlocking {
        val result = tester().test(ModelConfigDraft("not-a-url"))
        assertInstanceOf(ConnectionTestResult.InvalidResponse::class.java, result)
    }
}
