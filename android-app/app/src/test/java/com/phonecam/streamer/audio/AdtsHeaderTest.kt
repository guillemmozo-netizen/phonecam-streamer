package com.phonecam.streamer.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ADTS header is seven bytes of hand-packed bit fields that the PC's
 * decoder trusts absolutely — get one field wrong and the audio either
 * decodes as noise or not at all, with nothing on either side able to say
 * which field was wrong. So the packing is parsed back apart here, field by
 * field, rather than compared against a blob that could be wrong in the same
 * way twice.
 *
 * pc_receiver/aac.py builds and parses the identical bytes; its own tests
 * assert the same properties from the other side.
 */
class AdtsHeaderTest {

    private fun header(bytes: ByteArray) = bytes.map { it.toInt() and 0xFF }

    @Test
    fun `syncword and fixed fields mark it as MPEG-4 AAC-LC without CRC`() {
        val out = header(AdtsHeader.wrap(ByteArray(100), 100, 48000, 2))

        assertEquals(0xFF, out[0])
        // 0xF1 = syncword's low nibble, MPEG-4 (0), layer 00, protection absent (1)
        assertEquals(0xF1, out[1])
        assertEquals("profile must be AAC-LC", 1, (out[2] shr 6) and 0x03)
        assertEquals("one raw data block per frame", 0, out[6] and 0x03)
    }

    @Test
    fun `frame length field counts the header plus the payload`() {
        val out = header(AdtsHeader.wrap(ByteArray(500), 500, 48000, 2))

        val frameLength = ((out[3] and 0x03) shl 11) or (out[4] shl 3) or ((out[5] shr 5) and 0x07)
        assertEquals(507, frameLength)
    }

    @Test
    fun `every sample rate Settings offers is encodable`() {
        // 44.1 / 48 / 96 kHz are the three the spinner exposes; 16k covers
        // the low end a device might fall back to.
        val expectedIndex = mapOf(96000 to 0, 48000 to 3, 44100 to 4, 16000 to 8)
        for ((rate, index) in expectedIndex) {
            val out = header(AdtsHeader.wrap(ByteArray(10), 10, rate, 2))
            assertEquals("sampling_frequency_index for ${rate}Hz", index, (out[2] shr 2) and 0x0F)
        }
    }

    @Test
    fun `channel configuration survives across the byte boundary it straddles`() {
        // channel_configuration is split: its top bit is the last bit of
        // byte 2, its low two bits are the top of byte 3. An off-by-one here
        // is exactly the kind of thing that silently produces mono-as-stereo.
        for (channels in 1..7) {
            val out = header(AdtsHeader.wrap(ByteArray(10), 10, 48000, channels))
            val decoded = ((out[2] and 0x01) shl 2) or ((out[3] shr 6) and 0x03)
            assertEquals("channel_configuration for $channels", channels, decoded)
        }
    }

    @Test
    fun `payload follows the header untouched`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val framed = AdtsHeader.wrap(payload, payload.size, 48000, 2)

        assertEquals(AdtsHeader.SIZE_BYTES + payload.size, framed.size)
        assertArrayEquals(payload, framed.copyOfRange(AdtsHeader.SIZE_BYTES, framed.size))
    }

    @Test
    fun `only the requested prefix of the payload is framed`() {
        // MediaCodec hands back a reusable buffer whose valid length is
        // bufferInfo.size, not the array's length.
        val buffer = ByteArray(64) { it.toByte() }

        val framed = AdtsHeader.wrap(buffer, 10, 48000, 2)

        assertEquals(AdtsHeader.SIZE_BYTES + 10, framed.size)
        assertArrayEquals(buffer.copyOfRange(0, 10), framed.copyOfRange(AdtsHeader.SIZE_BYTES, framed.size))
    }

    @Test
    fun `sample rate support is reported before an encoder is built on it`() {
        assertTrue(AdtsHeader.supportsSampleRate(48000))
        assertTrue(AdtsHeader.supportsSampleRate(44100))
        assertTrue(AdtsHeader.supportsSampleRate(96000))
        assertFalse(AdtsHeader.supportsSampleRate(48001))
        assertFalse(AdtsHeader.supportsSampleRate(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a sample rate ADTS cannot express is rejected`() {
        AdtsHeader.wrap(ByteArray(10), 10, 48001, 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero channels is rejected`() {
        AdtsHeader.wrap(ByteArray(10), 10, 48000, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a frame too large for the 13-bit length field is rejected`() {
        AdtsHeader.wrap(ByteArray(1 shl 13), 1 shl 13, 48000, 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a length longer than the payload is rejected`() {
        AdtsHeader.wrap(ByteArray(10), 20, 48000, 2)
    }
}
