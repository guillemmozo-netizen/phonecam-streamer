package com.phonecam.streamer.streaming

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chunk header is the one piece of framing both sides have to agree on
 * byte for byte — an off-by-one here does not degrade the stream, it
 * destroys every message on it. So the bytes are decoded back here the way
 * pc_receiver/protocol.py's `>Bq` unpack would read them, rather than
 * assumed correct because the code looks right.
 */
class MediaChunkTest {

    /** Reads a chunk the way struct.unpack(">Bq", ...) does on the PC side. */
    private fun unpack(packed: ByteArray): Triple<Int, Long, ByteArray> {
        val kind = packed[0].toInt() and 0xFF
        var pts = 0L
        for (i in 1..8) {
            pts = (pts shl 8) or (packed[i].toLong() and 0xFF)
        }
        return Triple(kind, pts, packed.copyOfRange(MediaChunk.HEADER_BYTES, packed.size))
    }

    @Test
    fun `header is nine bytes, kind then a big-endian timestamp`() {
        assertEquals(9, MediaChunk.HEADER_BYTES)
        assertEquals(9, MediaChunk.pack(MediaKind.VIDEO, 0, ByteArray(0)).size)
    }

    @Test
    fun `video and audio round trip with their payloads intact`() {
        val payload = byteArrayOf(10, 20, 30, 40)

        val (videoKind, videoPts, videoData) = unpack(MediaChunk.pack(MediaKind.VIDEO, 1_234_567, payload))
        assertEquals(1, videoKind)
        assertEquals(1_234_567L, videoPts)
        assertArrayEquals(payload, videoData)

        val (audioKind, audioPts, audioData) = unpack(MediaChunk.pack(MediaKind.AUDIO, 42, payload))
        assertEquals(2, audioKind)
        assertEquals(42L, audioPts)
        assertArrayEquals(payload, audioData)
    }

    @Test
    fun `wire values match the receiver's MediaKind enum`() {
        assertEquals(1, MediaKind.VIDEO.wireValue)
        assertEquals(2, MediaKind.AUDIO.wireValue)
    }

    @Test
    fun `timestamps are big-endian`() {
        val packed = MediaChunk.pack(MediaKind.VIDEO, 0x0102030405060708L, ByteArray(0))

        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            packed.copyOfRange(1, 9),
        )
    }

    @Test
    fun `a negative timestamp survives as the same signed value`() {
        // nanoTime's origin is arbitrary and starts negative on some devices.
        // Read back as signed — which is what Python's `q` does — it has to
        // come out unchanged.
        val packed = MediaChunk.pack(MediaKind.AUDIO, -1_500_000L, byteArrayOf(9))

        val (_, pts, data) = unpack(packed)
        assertEquals(-1_500_000L, pts)
        assertArrayEquals(byteArrayOf(9), data)
    }

    @Test
    fun `extreme timestamps do not wrap`() {
        for (pts in listOf(Long.MAX_VALUE, Long.MIN_VALUE, 0L, -1L)) {
            val (_, decoded, _) = unpack(MediaChunk.pack(MediaKind.VIDEO, pts, ByteArray(0)))
            assertEquals(pts, decoded)
        }
    }

    @Test
    fun `only the requested prefix of a reused buffer is packed`() {
        // MediaCodec output buffers are longer than the data in them; the
        // valid length is bufferInfo.size.
        val buffer = ByteArray(32) { it.toByte() }

        val packed = MediaChunk.pack(MediaKind.AUDIO, 0, buffer, payloadLength = 5)

        val (_, _, data) = unpack(packed)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), data)
    }

    @Test
    fun `an empty payload is a valid chunk`() {
        val (kind, pts, data) = unpack(MediaChunk.pack(MediaKind.VIDEO, 7, ByteArray(0)))

        assertEquals(1, kind)
        assertEquals(7L, pts)
        assertEquals(0, data.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a length longer than the payload is rejected`() {
        MediaChunk.pack(MediaKind.VIDEO, 0, ByteArray(4), payloadLength = 10)
    }
}
