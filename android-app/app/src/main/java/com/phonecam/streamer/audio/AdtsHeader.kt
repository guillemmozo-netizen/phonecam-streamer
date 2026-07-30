package com.phonecam.streamer.audio

/**
 * ADTS framing for the AAC access units MediaCodec produces.
 *
 * MediaCodec's AAC encoder emits *raw* access units plus a one-off
 * AudioSpecificConfig (csd-0). Sending it that way would mean the PC needs
 * that config before it can decode anything — and the phone reconnects
 * mid-session (cable pulled, PC asleep, receiver restarted), with each
 * reconnect getting a brand-new decoder on the far side. Any config sent once
 * up front would have to be re-sent on every reconnect, and a stream that
 * missed it would decode nothing at all, silently.
 *
 * ADTS avoids the whole problem: these 7 bytes re-state the profile, sample
 * rate and channel count on every single frame, so the stream can be joined
 * anywhere. The cost is 7 bytes per ~21ms frame — about 2.6 kbit/s against a
 * 192 kbit/s stream.
 *
 * Kept free of Android types on purpose, so the bit packing — the part that
 * is easy to get subtly wrong and impossible to eyeball — is unit tested on
 * the JVM. The PC side builds and parses the identical bytes in
 * pc_receiver/aac.py.
 */
object AdtsHeader {

    const val SIZE_BYTES = 7

    /** MPEG-4 sampling_frequency_index: the index, not the rate, goes in the header. */
    private val SAMPLE_RATE_INDEX = mapOf(
        96000 to 0, 88200 to 1, 64000 to 2, 48000 to 3, 44100 to 4, 32000 to 5,
        24000 to 6, 22050 to 7, 16000 to 8, 12000 to 9, 11025 to 10, 8000 to 11, 7350 to 12,
    )

    /** AAC-LC. The field holds audioObjectType - 1, so LC (object type 2) is written as 1. */
    private const val PROFILE_AAC_LC = 1

    /** Whether [sampleRate] can be expressed in an ADTS header at all. */
    fun supportsSampleRate(sampleRate: Int): Boolean = SAMPLE_RATE_INDEX.containsKey(sampleRate)

    /**
     * Returns one complete ADTS frame: the 7-byte header followed by
     * [payload]'s first [payloadLength] bytes.
     *
     * Bit layout, MSB first (ISO/IEC 14496-3):
     *
     *     syncword                        12  always 0xFFF
     *     mpeg_version                     1  0 = MPEG-4
     *     layer                            2  always 0
     *     protection_absent                1  1 = no CRC follows
     *     profile                          2  AAC-LC
     *     sampling_frequency_index         4  see SAMPLE_RATE_INDEX
     *     private_bit                      1  0
     *     channel_configuration            3  1 = mono, 2 = stereo
     *     originality/home/copyright x2    4  0
     *     frame_length                    13  header + payload, the whole frame
     *     buffer_fullness                 11  0x7FF = variable rate, "don't care"
     *     raw_data_blocks_in_frame - 1     2  0, one block per frame
     */
    fun wrap(payload: ByteArray, payloadLength: Int, sampleRate: Int, channels: Int): ByteArray {
        val rateIndex = SAMPLE_RATE_INDEX[sampleRate]
            ?: throw IllegalArgumentException("${sampleRate}Hz is not an MPEG-4 sample rate")
        require(channels in 1..7) { "channel_configuration must be 1..7, got $channels" }
        require(payloadLength >= 0 && payloadLength <= payload.size) {
            "payloadLength $payloadLength outside a ${payload.size}-byte payload"
        }

        val frameLength = payloadLength + SIZE_BYTES
        require(frameLength < (1 shl 13)) {
            "frame of $frameLength bytes overflows ADTS's 13-bit length field"
        }

        val out = ByteArray(frameLength)
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte() // MPEG-4, layer 0, protection absent (no CRC)
        out[2] = (((PROFILE_AAC_LC shl 6) or (rateIndex shl 2) or ((channels shr 2) and 0x01))).toByte()
        out[3] = ((((channels and 0x03) shl 6) or ((frameLength shr 11) and 0x03))).toByte()
        out[4] = ((frameLength shr 3) and 0xFF).toByte()
        out[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte() // length's low bits, then buffer fullness
        out[6] = 0xFC.toByte() // rest of buffer fullness, and 0 extra raw data blocks
        payload.copyInto(out, destinationOffset = SIZE_BYTES, startIndex = 0, endIndex = payloadLength)
        return out
    }
}
