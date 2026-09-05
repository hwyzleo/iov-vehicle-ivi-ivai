package net.hwyz.iov.vehicle.ivi.ivai.speech.config

import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ApiKeyAction
import net.hwyz.iov.vehicle.ivi.ivai.model.config.ConnectionTestResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ASR connectivity test (IVI-IVAI-DSN-CR-006 / IVAI-REQ-053): Android providers
 * need no network; remote providers map HTTP results to Success / Unauthorized /
 * InvalidResponse; the Authorization header is sent when a key is typed.
 */
class AsrConnectionTesterTest {

    private lateinit var server: MockWebServer
    private val tester = AsrConnectionTester(
        connectTimeoutMs = 1_000,
        readTimeoutMs = 1_000,
        totalTimeoutMs = 5_000
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun remoteDraft(baseUrl: String = server.url("/v1").toString()): AsrConfigDraft =
        AsrConfigDraft(
            providerType = AsrProviderType.HTTP_COMPATIBLE,
            baseUrl = baseUrl,
            modelName = "whisper"
        )

    @Test
    fun `android provider succeeds locally without network`(): Unit = runBlocking {
        val result = tester.test(AsrConfigDraft(providerType = AsrProviderType.ANDROID_ON_DEVICE))
        assertInstanceOf(ConnectionTestResult.Success::class.java, result)
        assertEquals("android-local", (result as ConnectionTestResult.Success).testMethod)
    }

    @Test
    fun `http 200 maps to success`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = tester.test(remoteDraft())
        assertInstanceOf(ConnectionTestResult.Success::class.java, result)
    }

    @Test
    fun `http 401 maps to unauthorized`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = tester.test(remoteDraft())
        assertEquals(ConnectionTestResult.Unauthorized, result)
    }

    @Test
    fun `http 500 maps to invalid response`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = tester.test(remoteDraft())
        assertInstanceOf(ConnectionTestResult.InvalidResponse::class.java, result)
    }

    @Test
    fun `network failure maps to network error`(): Unit = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        val result = tester.test(remoteDraft())
        assertTrue(result is ConnectionTestResult.NetworkError || result is ConnectionTestResult.Timeout)
    }

    @Test
    fun `invalid draft returns invalid response without network`(): Unit = runBlocking {
        val result = tester.test(
            AsrConfigDraft(
                providerType = AsrProviderType.HTTP_COMPATIBLE,
                baseUrl = "not-a-url",
                modelName = ""
            )
        )
        assertInstanceOf(ConnectionTestResult.InvalidResponse::class.java, result)
    }

    @Test
    fun `typed key is sent as bearer authorization`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        tester.test(remoteDraft().copy(apiKeyAction = ApiKeyAction.Replace("sk-test")))

        val request = server.takeRequest()
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
    }
}
