package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * WAV/RIFF 44-byte header generation (IVI-IVAI-DSN-CR-007): RIFF/WAVE markers,
 * lengths, sample rate, channels, byte rate, block align, bit depth and
 * little-endian PCM payload order.
 */
class WavEncoderTest {

    private fun readAscii(data: ByteArray, offset: Int, length: Int): String =
        data.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun readIntLe(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLe(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `produces the standard 44 byte header plus payload`() {
        val pcm = ByteArray(3200) // 100 ms of 16 kHz mono 16-bit
        val wav = WavEncoder.encode(pcm)
        assertEquals(WavEncoder.HEADER_SIZE + pcm.size, wav.size)
    }

    @Test
    fun `header markers are correct`() {
        val wav = WavEncoder.encode(ByteArray(64))
        assertEquals("RIFF", readAscii(wav, 0, 4))
        assertEquals("WAVE", readAscii(wav, 8, 4))
        assertEquals("fmt ", readAscii(wav, 12, 4))
        assertEquals("data", readAscii(wav, 36, 4))
    }

    @Test
    fun `file length is 36 plus data size`() {
        val dataSize = 128
        val wav = WavEncoder.encode(ByteArray(dataSize))
        assertEquals(36 + dataSize, readIntLe(wav, 4))
    }

    @Test
    fun `fmt chunk describes pcm mono 16k 16 bit`() {
        val wav = WavEncoder.encode(ByteArray(64))
        assertEquals(16, readIntLe(wav, 16))        // fmt chunk size
        assertEquals(1, readShortLe(wav, 20))        // PCM format
        assertEquals(1, readShortLe(wav, 22))        // mono
        assertEquals(16_000, readIntLe(wav, 24))     // sample rate
        assertEquals(16_000 * 2, readIntLe(wav, 28)) // byte rate = sampleRate * blockAlign
        assertEquals(2, readShortLe(wav, 32))        // block align
        assertEquals(16, readShortLe(wav, 34))       // bit depth
    }

    @Test
    fun `data length matches the pcm payload`() {
        val pcm = ByteArray(400)
        val wav = WavEncoder.encode(pcm)
        assertEquals(pcm.size, readIntLe(wav, 40))
        assertArrayEquals(pcm, wav.copyOfRange(WavEncoder.HEADER_SIZE, wav.size))
    }

    @Test
    fun `pcm little endian byte order is preserved`() {
        // -2 (0xFFFE) then 300 (0x012C) as signed 16-bit LE samples.
        val pcm = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x2C.toByte(), 0x01.toByte())
        val wav = WavEncoder.encode(pcm)
        assertArrayEquals(pcm, wav.copyOfRange(WavEncoder.HEADER_SIZE, wav.size))
    }

    @Test
    fun `empty pcm still yields a valid 44 byte header`() {
        val wav = WavEncoder.encode(ByteArray(0))
        assertEquals(44, wav.size)
        assertEquals(36, readIntLe(wav, 4))
        assertEquals(0, readIntLe(wav, 40))
    }

    @Test
    fun `rejects non frame aligned pcm`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavEncoder.encode(ByteArray(1)) // odd byte for 16-bit mono
        }
    }

    @Test
    fun `rejects invalid format fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavEncoder.encode(ByteArray(4), sampleRate = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavEncoder.encode(ByteArray(4), channels = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavEncoder.encode(ByteArray(4), bitsPerSample = 9) // not multiple of 8
        }
    }
}
