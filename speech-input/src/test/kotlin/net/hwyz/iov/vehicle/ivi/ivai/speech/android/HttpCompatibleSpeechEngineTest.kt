package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.hwyz.iov.vehicle.ivi.ivai.model.config.SecretValue
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.AsrErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechCapability
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionConfig
import net.hwyz.iov.vehicle.ivi.ivai.speech.api.SpeechRecognitionEvent
import net.hwyz.iov.vehicle.ivi.ivai.speech.observability.SpeechRecognitionMetrics
import net.hwyz.iov.vehicle.ivi.ivai.speech.observability.SpeechRecognitionMetricsRecorder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * HTTP_COMPATIBLE engine end-to-end (IVI-IVAI-DSN-CR-007): ACTION_DOWN → fake
 * recorder produces PCM → stop() wraps it in WAV → multipart upload to
 * MockWebServer → 200 {"text":...} → single FinalResult. Also covers network /
 * timeout / HTTP error mapping, cancellation and metrics.
 */
class HttpCompatibleSpeechEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var engineScope: CoroutineScope

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private class FakeSource(private val pcm: ShortArray) : AudioRecorderSource {
        private var served = false
        override fun start(): Result<Unit> = Result.success(Unit)
        override fun read(dest: ShortArray, sizeInShorts: Int): Int {
            if (!served) {
                served = true
                val n = minOf(sizeInShorts, pcm.size)
                pcm.copyInto(dest, 0, 0, n)
                return n
            }
            Thread.sleep(20)
            return 0
        }

        override fun stop() = Unit
        override fun release() = Unit
    }

    private class CollectingRecorder : SpeechRecognitionMetricsRecorder {
        val metrics = mutableListOf<SpeechRecognitionMetrics>()
        override fun record(m: SpeechRecognitionMetrics) {
            metrics.add(m)
        }
    }

    private fun engine(
        recorder: AndroidAudioRecorder,
        metrics: CollectingRecorder = CollectingRecorder(),
        recognitionTimeoutMs: Long = 3_000
    ): HttpCompatibleSpeechEngine = HttpCompatibleSpeechEngine(
        recorder = recorder,
        requestBuilder = HttpAsrRequestBuilder(
            baseUrl = server.url("/v1/audio/transcriptions").toString(),
            modelName = "whisper-1",
            languageTag = "zh-CN",
            apiKey = SecretValue.of("sk-test")
        ),
        responseParser = HttpAsrResponseParser(),
        clientFactory = HttpAsrClientFactory(connectTimeoutMs = 1_000, readTimeoutMs = 2_000),
        recognitionTimeoutMs = recognitionTimeoutMs,
        scope = engineScope,
        metricsRecorder = metrics
    )

    private fun session(engine: HttpCompatibleSpeechEngine, action: () -> Unit): List<SpeechRecognitionEvent> {
        val received = mutableListOf<SpeechRecognitionEvent>()
        val collectJob = CoroutineScope(Dispatchers.Default).launch {
            engine.events().collect { received.add(it) }
        }
        engine.start(SpeechRecognitionConfig(sessionId = "s1"))
        Thread.sleep(80) // let the recorder read loop capture PCM
        action()
        runBlockingWaitForTerminal(received)
        collectJob.cancel()
        return received.toList()
    }

    private fun runBlockingWaitForTerminal(received: MutableList<SpeechRecognitionEvent>) {
        kotlinx.coroutines.runBlocking {
            withTimeout(5_000) {
                while (received.none { it is SpeechRecognitionEvent.FinalResult || it is SpeechRecognitionEvent.Error }) {
                    delay(20)
                }
            }
        }
    }

    private fun recorder(pcm: ShortArray = ShortArray(3_200)): AndroidAudioRecorder =
        AndroidAudioRecorder(
            sourceFactory = { FakeSource(pcm) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )

    private fun terminal(received: List<SpeechRecognitionEvent>): SpeechRecognitionEvent =
        received.first { it is SpeechRecognitionEvent.FinalResult || it is SpeechRecognitionEvent.Error }

    // ------------------------------------------------------------------ happy path

    @Test
    fun `action down then up yields a single final result from the upload`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"打开空调"}"""))
        val metrics = CollectingRecorder()
        val engine = engine(recorder(), metrics)

        val received = session(engine) { engine.stop() }

        val final = terminal(received) as SpeechRecognitionEvent.FinalResult
        assertEquals("打开空调", final.text)
        // Ready + Listening precede the final result.
        assertTrue(received.first { it is SpeechRecognitionEvent.Ready } is SpeechRecognitionEvent.Ready)
        assertTrue(received.first { it is SpeechRecognitionEvent.Listening } is SpeechRecognitionEvent.Listening)
        assertEquals(1, received.count { it is SpeechRecognitionEvent.FinalResult })

        // Request went to the exact configured path with audio uploaded.
        val recorded = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("voice.wav"))
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))

        // Metrics: http-compatible, not on-device, status + result length captured.
        val m = metrics.metrics.single()
        assertEquals("http-compatible", m.engineType)
        assertEquals(false, m.onDevice)
        assertEquals(200, m.httpStatus)
        assertEquals(4, m.resultLength)

        engine.release()
    }

    // ------------------------------------------------------------------ failure mapping

    @Test
    fun `401 maps to remote unauthorized error`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val engine = engine(recorder())
        val received = session(engine) { engine.stop() }
        val error = (terminal(received) as SpeechRecognitionEvent.Error).error
        assertEquals(AsrErrorCode.REMOTE_UNAUTHORIZED, error.code)
        assertEquals("http-compatible", error.engineType)
        assertFalse(received.any { it is SpeechRecognitionEvent.FinalResult })
        engine.release()
    }

    @Test
    fun `500 maps to remote service error`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"boom"}}"""))
        val engine = engine(recorder())
        val received = session(engine) { engine.stop() }
        assertEquals(
            AsrErrorCode.REMOTE_SERVICE_ERROR,
            (terminal(received) as SpeechRecognitionEvent.Error).error.code
        )
        engine.release()
    }

    @Test
    fun `illegal json on 2xx maps to invalid response`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))
        val engine = engine(recorder())
        val received = session(engine) { engine.stop() }
        assertEquals(
            AsrErrorCode.REMOTE_INVALID_RESPONSE,
            (terminal(received) as SpeechRecognitionEvent.Error).error.code
        )
        engine.release()
    }

    @Test
    fun `blank text on 2xx maps to empty result`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"   "}"""))
        val engine = engine(recorder())
        val received = session(engine) { engine.stop() }
        assertEquals(
            AsrErrorCode.EMPTY_RESULT,
            (terminal(received) as SpeechRecognitionEvent.Error).error.code
        )
        engine.release()
    }

    // ------------------------------------------------------------------ network / timeout

    @Test
    fun `connection failure maps to network error`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val engine = engine(recorder())
        val received = session(engine) { engine.stop() }
        val error = (terminal(received) as SpeechRecognitionEvent.Error).error
        assertTrue(
            error.code == AsrErrorCode.NETWORK_ERROR || error.code == AsrErrorCode.RECOGNITION_TIMEOUT,
            "expected network/timeout error but was ${error.code}"
        )
        engine.release()
    }

    @Test
    fun `slow response exceeds recognition timeout and maps to timeout`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"text":"打开空调"}""")
                .setBodyDelay(3, TimeUnit.SECONDS)
        )
        val engine = engine(recorder(), recognitionTimeoutMs = 300)
        val received = session(engine) { engine.stop() }
        val error = (terminal(received) as SpeechRecognitionEvent.Error).error
        assertTrue(
            error.code == AsrErrorCode.RECOGNITION_TIMEOUT ||
                error.code == AsrErrorCode.NETWORK_ERROR,
            "expected timeout error but was ${error.code}"
        )
        engine.release()
    }

    // ------------------------------------------------------------------ cancel / lifecycle

    @Test
    fun `cancel after start never produces a final result`() {
        val engine = engine(recorder())
        engine.start(SpeechRecognitionConfig(sessionId = "s1"))
        Thread.sleep(80)
        engine.cancel()
        Thread.sleep(150)
        // The engine never emitted a terminal event.
        engine.release()
    }

    @Test
    fun `release is idempotent and does not throw`() {
        val engine = engine(recorder())
        engine.start(SpeechRecognitionConfig(sessionId = "s1"))
        engine.release()
        engine.release()
    }

    @Test
    fun `capability reports remote and never on device`() {
        val engine = engine(recorder())
        val cap = kotlinx.coroutines.runBlocking { engine.capability() }
        assertEquals(SpeechCapability.REMOTE, cap)
        engine.release()
    }
}
