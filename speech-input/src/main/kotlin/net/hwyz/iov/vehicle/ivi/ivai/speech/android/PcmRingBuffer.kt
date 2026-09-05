package net.hwyz.iov.vehicle.ivi.ivai.speech.android

/**
 * Bounded in-memory PCM buffer (IVI-IVAI-DSN-CR-007).
 *
 * The recorder writes 16 kHz / 16-bit / mono PCM here while the user holds the
 * button. The buffer is capped: once full, [write] refuses further data instead
 * of overwriting already-captured audio, so an upload never carries an
 * incomplete / overwritten utterance. The caller (recorder) must stop the
 * session and surface a stable error when [write] reports "full".
 *
 * Thread-safe: the recording thread writes, the controller reads on stop().
 */
class PcmRingBuffer(private val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val buffer = ByteArray(capacity)
    private var writeIndex = 0
    private var size = 0

    /** Total byte capacity (never grows). */
    val capacityBytes: Int get() = capacity

    /** Bytes currently held. */
    @Synchronized
    fun sizeBytes(): Int = size

    /** True once the buffer can no longer accept data. */
    @Synchronized
    fun isFull(): Boolean = size >= capacity

    /**
     * Appends [length] bytes from [src] at [offset]. Returns false when there is
     * not enough free space — no data is written in that case (never overwrites).
     */
    @Synchronized
    fun write(src: ByteArray, offset: Int = 0, length: Int = src.size): Boolean {
        if (length <= 0) return true
        if (length > capacity - size) return false
        src.copyInto(buffer, writeIndex, offset, offset + length)
        writeIndex += length
        size += length
        return true
    }

    /** Copies the captured bytes in capture order (left to right, never wraps). */
    @Synchronized
    fun readAll(): ByteArray = buffer.copyOfRange(0, size)

    /** Empties the buffer (start of a new session / cancel / release). */
    @Synchronized
    fun clear() {
        writeIndex = 0
        size = 0
    }
}
