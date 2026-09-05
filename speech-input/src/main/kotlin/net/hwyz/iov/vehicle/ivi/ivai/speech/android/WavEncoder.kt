package net.hwyz.iov.vehicle.ivi.ivai.speech.android

/**
 * Encodes raw PCM into a WAV/RIFF container with the standard 44-byte header
 * (IVI-IVAI-DSN-CR-007).
 *
 * Fixed capture format: 16 kHz, PCM 16-bit signed little-endian, mono
 * (AudioFormat.CHANNEL_IN_MONO + ENCODING_PCM_16BIT). The header fields are
 * written little-endian as required by the RIFF spec.
 */
object WavEncoder {

    const val HEADER_SIZE = 44
    const val PCM_FORMAT = 1

    /**
     * Wraps [pcm] (16-bit signed little-endian, mono) with a 44-byte header.
     * [sampleRate], [channels] and [bitsPerSample] are validated for the fixed
     * contract; they default to 16 kHz / mono / 16-bit.
     *
     * @throws IllegalArgumentException when PCM length is not a whole number of
     *   frames for the given [channels] × [bitsPerSample], or when the fields
     *   are non-positive.
     */
    fun encode(
        pcm: ByteArray,
        sampleRate: Int = SAMPLE_RATE_HZ,
        channels: Int = MONO,
        bitsPerSample: Int = BITS_PER_SAMPLE
    ): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }
        require(bitsPerSample > 0 && bitsPerSample % 8 == 0) { "bitsPerSample must be a positive multiple of 8" }

        val blockAlign = channels * bitsPerSample / 8
        require(pcm.size % blockAlign == 0) { "PCM length is not frame-aligned" }

        val byteRate = sampleRate * blockAlign
        val dataSize = pcm.size
        val out = ByteArray(HEADER_SIZE + dataSize)

        writeAscii(out, 0, "RIFF")
        writeIntLe(out, 4, 36 + dataSize) // file size after "RIFF"
        writeAscii(out, 8, "WAVE")

        writeAscii(out, 12, "fmt ")
        writeIntLe(out, 16, 16)              // fmt chunk size
        writeShortLe(out, 20, PCM_FORMAT)    // PCM (uncompressed)
        writeShortLe(out, 22, channels)
        writeIntLe(out, 24, sampleRate)
        writeIntLe(out, 28, byteRate)
        writeShortLe(out, 32, blockAlign)
        writeShortLe(out, 34, bitsPerSample)

        writeAscii(out, 36, "data")
        writeIntLe(out, 40, dataSize)

        pcm.copyInto(out, HEADER_SIZE)
        return out
    }

    private fun writeAscii(dst: ByteArray, offset: Int, s: String) {
        for (i in s.indices) dst[offset + i] = s[i].code.toByte()
    }

    private fun writeIntLe(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value shr 8) and 0xFF).toByte()
        dst[offset + 2] = ((value shr 16) and 0xFF).toByte()
        dst[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLe(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private const val SAMPLE_RATE_HZ = 16_000
    private const val MONO = 1
    private const val BITS_PER_SAMPLE = 16
}
