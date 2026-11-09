package az.nvaton.komekci.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @Test
    fun `header describes 16 kHz mono 16-bit PCM`() {
        val pcm = ByteArray(3200) // 100 ms
        val wav = Wav.fromPcm16(pcm, 16_000)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcm.size, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28)) // byte rate
        assertEquals(16, b.getShort(34).toInt()) // bits per sample
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size, b.getInt(40))
    }
}
