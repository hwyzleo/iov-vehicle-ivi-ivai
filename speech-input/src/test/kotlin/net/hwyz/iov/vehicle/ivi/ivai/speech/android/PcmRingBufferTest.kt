package net.hwyz.iov.vehicle.ivi.ivai.speech.android

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bounded in-memory PCM buffer (IVI-IVAI-DSN-CR-007): caps at capacity, never
 * overwrites captured audio, and clears between sessions.
 */
class PcmRingBufferTest {

    @Test
    fun `rejects non positive capacity`() {
        assertThrows(IllegalArgumentException::class.java) { PcmRingBuffer(0) }
        assertThrows(IllegalArgumentException::class.java) { PcmRingBuffer(-1) }
    }

    @Test
    fun `starts empty and not full`() {
        val buffer = PcmRingBuffer(16)
        assertEquals(0, buffer.sizeBytes())
        assertFalse(buffer.isFull())
        assertArrayEquals(ByteArray(0), buffer.readAll())
    }

    @Test
    fun `writes and reads back in order`() {
        val buffer = PcmRingBuffer(16)
        assertTrue(buffer.write(byteArrayOf(1, 2, 3)))
        assertTrue(buffer.write(byteArrayOf(4, 5)))
        assertEquals(5, buffer.sizeBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), buffer.readAll())
    }

    @Test
    fun `full buffer refuses further writes without overwriting`() {
        val buffer = PcmRingBuffer(8)
        assertTrue(buffer.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertTrue(buffer.isFull())
        assertFalse(buffer.write(byteArrayOf(9, 9, 9)))
        // Captured bytes stay untouched.
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), buffer.readAll())
    }

    @Test
    fun `partial write fails atomically when not enough room`() {
        val buffer = PcmRingBuffer(6)
        assertTrue(buffer.write(byteArrayOf(1, 2, 3, 4)))
        assertFalse(buffer.write(byteArrayOf(5, 5, 5, 5))) // needs 4, only 2 free
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer.readAll())
    }

    @Test
    fun `clear resets for the next session`() {
        val buffer = PcmRingBuffer(8)
        buffer.write(byteArrayOf(1, 2, 3))
        buffer.clear()
        assertEquals(0, buffer.sizeBytes())
        assertFalse(buffer.isFull())
        assertTrue(buffer.write(byteArrayOf(9)))
        assertArrayEquals(byteArrayOf(9), buffer.readAll())
    }

    @Test
    fun `zero length write is a no-op success`() {
        val buffer = PcmRingBuffer(4)
        assertTrue(buffer.write(ByteArray(0)))
        assertEquals(0, buffer.sizeBytes())
    }

    @Test
    fun `supports offset and length slicing`() {
        val buffer = PcmRingBuffer(8)
        val data = byteArrayOf(0, 10, 20, 30, 0)
        assertTrue(buffer.write(data, offset = 1, length = 3))
        assertArrayEquals(byteArrayOf(10, 20, 30), buffer.readAll())
    }
}
