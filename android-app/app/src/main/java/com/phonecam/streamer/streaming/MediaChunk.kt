package com.phonecam.streamer.streaming

/**
 * Tagging for the messages that share one socket once audio is on.
 *
 * Counterpart to pack_chunk/unpack_chunk in pc_receiver/protocol.py; see
 * that module for why audio and video share a connection instead of opening
 * two. Framing is otherwise untouched — this is a 9-byte header *inside* the
 * existing length-prefixed payload, not a change to the framing itself:
 *
 *     [4-byte length][1-byte kind][8-byte pts_us][encoded data]
 *
 * Senders with no audio keep writing bare payloads, byte for byte as before,
 * which is what lets an older phone and this receiver still understand each
 * other. Hello.audioCodec is the single switch: non-empty means tagged.
 *
 * Free of Android and socket types so the byte layout — where an off-by-one
 * would corrupt every message on the wire — is unit tested on the JVM.
 */
enum class MediaKind(val wireValue: Int) {
    VIDEO(1),
    AUDIO(2),
}

object MediaChunk {

    const val HEADER_BYTES = 9

    /**
     * Prefixes [payload] with its stream and presentation timestamp.
     *
     * [ptsUs] is microseconds from System.nanoTime(), the same clock video
     * frames are stamped with — that shared origin is the only reason the PC
     * can align the two streams at all. Written as a signed 64-bit value
     * because nanoTime's origin is arbitrary and starts negative on some
     * devices; an unsigned field would turn that into a 584,000-year offset.
     */
    fun pack(kind: MediaKind, ptsUs: Long, payload: ByteArray, payloadLength: Int = payload.size): ByteArray {
        require(payloadLength in 0..payload.size) {
            "payloadLength $payloadLength outside a ${payload.size}-byte payload"
        }
        val out = ByteArray(HEADER_BYTES + payloadLength)
        out[0] = kind.wireValue.toByte()
        for (i in 0 until 8) {
            // Big-endian, matching the length prefix already on the wire and
            // Python's ">Bq" on the far side.
            out[1 + i] = ((ptsUs shr (56 - 8 * i)) and 0xFF).toByte()
        }
        payload.copyInto(out, destinationOffset = HEADER_BYTES, startIndex = 0, endIndex = payloadLength)
        return out
    }
}
