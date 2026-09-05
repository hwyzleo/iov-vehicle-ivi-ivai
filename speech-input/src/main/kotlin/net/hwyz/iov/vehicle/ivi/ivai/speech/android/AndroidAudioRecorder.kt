package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AudioRecord-backed recorder (IVI-IVAI-DSN-CR-007).
 *
 * Captures 16 kHz / 16-bit / mono PCM into a bounded [PcmRingBuffer] (memory
 * only — never written to disk). A reading coroutine fills the buffer while the
 * user holds the button; when the buffer reaches its cap the session stops and
 * [onBufferOverflow] fires instead of overwriting already-captured audio.
 *
 * The Android [AudioRecord] is hidden behind [AudioRecorderSource] so the whole
 * state machine (start / stop / cancel / repeated calls / release) is
 * unit-testable without a device; the production source is created by
 * [AndroidAudioRecordSource].
 */
class AndroidAudioRecorder(
    private val sampleRate: Int = SAMPLE_RATE_HZ,
    private val maxRecordingBytes: Int = DEFAULT_MAX_RECORDING_BYTES,
    private val sourceFactory: () -> AudioRecorderSource = { throw IllegalStateException("no source factory") },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val ringBuffer = PcmRingBuffer(maxRecordingBytes)

    /** Fired when the buffer is full; the engine must surface a stable error. */
    var onBufferOverflow: (() -> Unit)? = null

    /** Fired when reading from the source fails mid-session. */
    var onReadError: (() -> Unit)? = null

    private var readJob: Job? = null
    private var source: AudioRecorderSource? = null

    @Volatile
    private var recording = false

    /** Starts a new capture for [sessionId]; fails when a session is already active. */
    fun start(sessionId: String): Result<Unit> {
        if (recording) return Result.failure(IllegalStateException("已有录音会话在进行中"))
        val created = runCatching { sourceFactory() }.getOrElse { e ->
            return Result.failure(e)
        }
        val started = created.start()
        if (started.isFailure) {
            created.release()
            return Result.failure(started.exceptionOrNull() ?: IllegalStateException("录音启动失败"))
        }

        ringBuffer.clear()
        source = created
        recording = true
        readJob = scope.launch { readLoop(created) }
        return Result.success(Unit)
    }

    /**
     * Stops recording and returns the captured PCM. Fails when no session is
     * active or the capture was aborted.
     */
    fun stop(): Result<ByteArray> {
        val active = source ?: return Result.failure(IllegalStateException("没有进行中的录音会话"))
        if (!recording) return Result.failure(IllegalStateException("录音会话未在进行"))
        recording = false
        readJob?.cancel()
        readJob = null
        active.stop()
        return Result.success(ringBuffer.readAll())
    }

    /** Aborts the session, clears captured audio; never returns PCM. Idempotent. */
    fun cancel() {
        recording = false
        readJob?.cancel()
        readJob = null
        source?.stop()
        ringBuffer.clear()
    }

    /** Releases the source, cancels the reading job and clears audio. Idempotent. */
    fun release() {
        recording = false
        readJob?.cancel()
        readJob = null
        source?.release()
        source = null
        ringBuffer.clear()
        scope.cancel()
    }

    private fun readLoop(active: AudioRecorderSource) {
        val shortBuf = ShortArray(READ_CHUNK_SHORTS)
        val byteBuf = ByteArray(shortBuf.size * 2)
        while (recording && scope.isActive) {
            val n = active.read(shortBuf, shortBuf.size)
            if (n < 0) {
                // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT etc.
                recording = false
                active.stop()
                onReadError?.invoke()
                return
            }
            if (n == 0) {
                Thread.sleep(IDLE_SLEEP_MS)
                continue
            }
            for (i in 0 until n) {
                val v = shortBuf[i].toInt()
                byteBuf[i * 2] = (v and 0xFF).toByte()
                byteBuf[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            if (!ringBuffer.write(byteBuf, 0, n * 2)) {
                // Buffer full → stop instead of overwriting captured audio.
                recording = false
                active.stop()
                onBufferOverflow?.invoke()
                return
            }
        }
        active.stop()
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_IN_MONO = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING_PCM_16BIT = AudioFormat.ENCODING_PCM_16BIT

        /** ~100 ms per read chunk (1600 shorts = 3200 bytes). */
        private const val READ_CHUNK_SHORTS = 1_600
        private const val IDLE_SLEEP_MS = 10L

        /** Cap = 16 kHz × 2 bytes × 30 s ≈ 960 KB (aligned with the default recognition timeout). */
        const val DEFAULT_MAX_RECORDING_SECONDS = 30
        const val DEFAULT_MAX_RECORDING_BYTES =
            SAMPLE_RATE_HZ * 2 * DEFAULT_MAX_RECORDING_SECONDS
    }
}

/**
 * Injectable low-level capture source that wraps [AudioRecord]. The engine and
 * tests talk to this, never to [AudioRecord] directly.
 */
interface AudioRecorderSource {
    /** Initializes + starts capturing; fails when permission/device is unavailable. */
    fun start(): Result<Unit>

    /** Reads up to [sizeInShorts] signed 16-bit samples; <0 is a platform error. */
    fun read(dest: ShortArray, sizeInShorts: Int): Int

    /** Stops capturing (keeps the instance reusable until release). */
    fun stop()

    /** Releases native resources. Idempotent. */
    fun release()
}

/**
 * Production [AudioRecorderSource] wrapping [AudioRecord].
 *
 * - Permission missing → SecurityException from construction (mapped by the
 *   engine to IVAI-ASR-001).
 * - Mic busy / initialization state abnormal → IllegalStateException (mapped to
 *   IVAI-ASR-007).
 */
class AndroidAudioRecordSource(
    private val sampleRate: Int = AndroidAudioRecorder.SAMPLE_RATE_HZ,
    private val channelConfig: Int = AndroidAudioRecorder.CHANNEL_IN_MONO,
    private val encoding: Int = AndroidAudioRecorder.ENCODING_PCM_16BIT,
    private val bufferSizeBytes: Int = AudioRecord.getMinBufferSize(
        AndroidAudioRecorder.SAMPLE_RATE_HZ,
        AndroidAudioRecorder.CHANNEL_IN_MONO,
        AndroidAudioRecorder.ENCODING_PCM_16BIT
    ).coerceAtLeast(MIN_BUFFER_BYTES)
) : AudioRecorderSource {

    private var record: AudioRecord? = null

    override fun start(): Result<Unit> = runCatching {
        val created = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            encoding,
            bufferSizeBytes
        )
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            throw IllegalStateException("AudioRecord 初始化失败（设备被占用或状态异常）")
        }
        created.startRecording()
        if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            created.release()
            throw IllegalStateException("录音启动失败（设备被占用）")
        }
        record = created
    }

    override fun read(dest: ShortArray, sizeInShorts: Int): Int =
        record?.read(dest, 0, sizeInShorts) ?: AudioRecord.ERROR_INVALID_OPERATION

    override fun stop() {
        record?.let {
            runCatching { it.stop() }
            record = null
        }
    }

    override fun release() {
        record?.let {
            runCatching { it.release() }
            record = null
        }
    }

    private companion object {
        const val MIN_BUFFER_BYTES = 4_096
    }
}
