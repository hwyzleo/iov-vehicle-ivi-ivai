package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AndroidAudioRecorder state machine (IVI-IVAI-DSN-CR-007) driven by an injected
 * fake [AudioRecorderSource] — covers start / stop / cancel / repeated calls,
 * source failure, buffer cap overflow and release, all without a device.
 */
class AndroidAudioRecorderTest {

    private class FakeSource(
        private val pcm: ShortArray = shortArrayOf(1, 2, 3, 4),
        private val failStart: Throwable? = null,
        private val failRead: Boolean = false,
        private val idleMs: Long = 20
    ) : AudioRecorderSource {
        var started = false
        var stopped = false
        var released = false
        private var served = false

        override fun start(): Result<Unit> {
            failStart?.let { return Result.failure(it) }
            started = true
            return Result.success(Unit)
        }

        override fun read(dest: ShortArray, sizeInShorts: Int): Int {
            if (failRead) return -3 // AudioRecord.ERROR_INVALID_OPERATION
            if (!served) {
                served = true
                val n = minOf(sizeInShorts, pcm.size)
                pcm.copyInto(dest, 0, 0, n)
                return n
            }
            Thread.sleep(idleMs)
            return 0
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            released = true
            stopped = true
        }
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun `start then stop returns the captured pcm`() {
        val source = FakeSource()
        val recorder = AndroidAudioRecorder(sourceFactory = { source }, scope = scope())
        assertEquals(true, recorder.start("s1").isSuccess)

        Thread.sleep(50) // let the read loop capture the PCM
        val pcm = recorder.stop().getOrThrow()
        // 4 shorts → 8 little-endian bytes.
        assertEquals(8, pcm.size)
        assertArrayEquals(
            byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0),
            pcm
        )
        assertTrue(source.stopped)
        recorder.release()
    }

    @Test
    fun `start again while recording fails`() {
        val source = FakeSource()
        val recorder = AndroidAudioRecorder(sourceFactory = { source }, scope = scope())
        recorder.start("s1")
        Thread.sleep(50)
        assertTrue(recorder.start("s2").isFailure)
        recorder.cancel()
        recorder.release()
    }

    @Test
    fun `stop without an active session fails`() {
        val recorder = AndroidAudioRecorder(sourceFactory = { FakeSource() }, scope = scope())
        assertTrue(recorder.stop().isFailure)
        recorder.release()
    }

    @Test
    fun `source start failure propagates as failure`() {
        val source = FakeSource(failStart = IllegalStateException("device busy"))
        val recorder = AndroidAudioRecorder(sourceFactory = { source }, scope = scope())
        val result = recorder.start("s1")
        assertTrue(result.isFailure)
        assertEquals("device busy", result.exceptionOrNull()?.message)
        // The failed source must be released by the recorder.
        assertTrue(source.released)
        recorder.release()
    }

    @Test
    fun `cancel clears captured audio and a later stop fails`() {
        val source = FakeSource()
        val recorder = AndroidAudioRecorder(sourceFactory = { source }, scope = scope())
        recorder.start("s1")
        Thread.sleep(50)
        recorder.cancel()
        assertTrue(recorder.stop().isFailure) // no active session anymore
        assertTrue(source.stopped)
        recorder.release()
    }

    @Test
    fun `buffer cap overflow fires the callback and stops the session`() {
        val overflow = AtomicInteger(0)
        val source = FakeSource(pcm = ShortArray(10_000))
        val recorder = AndroidAudioRecorder(
            maxRecordingBytes = 4, // 2 shorts
            sourceFactory = { source },
            scope = scope()
        )
        recorder.onBufferOverflow = { overflow.incrementAndGet() }
        recorder.start("s1")
        Thread.sleep(100)
        assertTrue(overflow.get() > 0)
        assertTrue(source.stopped)
        recorder.cancel()
        recorder.release()
    }

    @Test
    fun `read error fires the callback`() {
        val readError = AtomicInteger(0)
        val source = FakeSource(failRead = true)
        val recorder = AndroidAudioRecorder(sourceFactory = { source }, scope = scope())
        recorder.onReadError = { readError.incrementAndGet() }
        recorder.start("s1")
        Thread.sleep(100)
        assertTrue(readError.get() > 0)
        assertTrue(source.stopped)
        recorder.release()
    }

    @Test
    fun `release is idempotent and releases the source`() {
        val source = FakeSource()
        val recorder = AndroidAudioRecorder(sourceFactory = { source }, scope = scope())
        recorder.start("s1")
        Thread.sleep(50)
        recorder.release()
        recorder.release()
        assertTrue(source.released)
    }
}
