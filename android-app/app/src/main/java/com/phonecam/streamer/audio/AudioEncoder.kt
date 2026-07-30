package com.phonecam.streamer.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AudioEncoder"

/** Never block the capture thread waiting on the codec — same policy as H264Encoder. */
private const val DEQUEUE_TIMEOUT_US = 0L

/**
 * Encodes captured PCM for the wire. Two codecs, and the choice between them
 * is a fallback rather than a preference:
 *
 *  - **AAC-LC** ([AacAudioEncoder]) is what every session should use.
 *    MediaCodec's AAC-LC encoder is the one audio encoder Android requires
 *    every device to ship, and ~192 kbit/s of AAC against ~1.5 Mbit/s of raw
 *    stereo matters on a shared link even when video dwarfs both.
 *  - **PCM** ([PcmAudioEncoder]) is a passthrough for when that encoder will
 *    not start. It cannot fail for codec reasons because there is no codec,
 *    so "the microphone works" never ends up depending on a vendor
 *    implementation behaving.
 *
 * Opus and FLAC appear in Settings but are not implemented here: MediaCodec's
 * Opus *encoder* is API 29+ and inconsistently present in practice, and FLAC
 * is a lossless archival codec whose bitrate makes no sense for a live
 * stream. Both fall back to AAC — see [forCodecName], which logs when it
 * does so rather than silently pretending.
 */
interface AudioEncoder {

    /** What goes in Hello.audio_codec, so the PC picks the matching decoder. */
    val codecName: String

    /**
     * Encodes [sampleCount] shorts of interleaved 16-bit PCM and returns
     * whatever is ready now — usually one packet, sometimes zero (the codec
     * is priming) and occasionally several.
     */
    fun encode(pcm: ShortArray, sampleCount: Int, presentationTimeUs: Long): List<ByteArray>

    fun release()
}

/**
 * AAC-LC via MediaCodec, ADTS-framed.
 *
 * Not thread-safe: [encode] and [release] must be called from the same
 * thread — AudioStreamer's capture thread, with release ordered after the
 * loop has stopped.
 */
class AacAudioEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    bitrateBps: Int,
) : AudioEncoder {

    override val codecName = "aac"

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var released = false

    init {
        require(AdtsHeader.supportsSampleRate(sampleRate)) {
            "${sampleRate}Hz cannot be expressed in an ADTS header"
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            // Room for a whole AAC frame of PCM at this format, with slack.
            // Left to the default, some encoders size this smaller than the
            // buffers the capture thread hands over and reject them.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 2 * channels * 2048)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        Log.i(TAG, "AAC-LC encoder: ${sampleRate}Hz ${channels}ch ${bitrateBps / 1000}kbps")
    }

    override fun encode(pcm: ShortArray, sampleCount: Int, presentationTimeUs: Long): List<ByteArray> {
        if (released || sampleCount <= 0) return emptyList()

        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex >= 0) {
            val input = codec.getInputBuffer(inputIndex)
            if (input != null) {
                input.clear()
                // Little-endian explicitly: MediaCodec's PCM buffers are
                // native-endian and every Android device in practice is
                // little-endian, but the wire format says s16le and an
                // assumption that silently holds is still worth stating.
                val shorts = input.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val take = minOf(sampleCount, shorts.remaining())
                shorts.put(pcm, 0, take)
                codec.queueInputBuffer(inputIndex, 0, take * 2, presentationTimeUs, 0)
            } else {
                codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
            }
        }
        // A dropped input buffer (inputIndex < 0, codec momentarily full) is
        // survivable: it costs one 21ms frame. Blocking the capture thread to
        // wait for one would instead cost an AudioRecord overrun, which loses
        // more.
        return drainOutput()
    }

    private fun drainOutput(): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return packets
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                outputIndex >= 0 -> {
                    val output = codec.getOutputBuffer(outputIndex)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    // The codec-config buffer is the AudioSpecificConfig, and
                    // it is deliberately dropped: every ADTS header carries
                    // the same information, on every frame, which is what
                    // makes the stream joinable after a reconnect. See
                    // AdtsHeader's doc.
                    if (output != null && bufferInfo.size > 0 && !isConfig) {
                        packets += toAdts(output, bufferInfo.offset, bufferInfo.size)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
                else -> return packets
            }
        }
    }

    private fun toAdts(buffer: ByteBuffer, offset: Int, size: Int): ByteArray {
        val raw = ByteArray(size)
        buffer.position(offset)
        buffer.limit(offset + size)
        buffer.get(raw)
        return AdtsHeader.wrap(raw, size, sampleRate, channels)
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }.onFailure { Log.w(TAG, "codec.stop() failed", it) }
        runCatching { codec.release() }.onFailure { Log.w(TAG, "codec.release() failed", it) }
    }
}

/**
 * Raw interleaved 16-bit little-endian PCM, no codec involved.
 *
 * The fallback when AAC will not start, and the reason "audio works" is not
 * hostage to a vendor encoder. Costs ~1.5 Mbit/s at 48kHz stereo, which is
 * real but small next to the 20-50 Mbit/s of video already on the link.
 */
class PcmAudioEncoder : AudioEncoder {

    override val codecName = "pcm_s16le"

    override fun encode(pcm: ShortArray, sampleCount: Int, presentationTimeUs: Long): List<ByteArray> {
        if (sampleCount <= 0) return emptyList()
        val out = ByteArray(sampleCount * 2)
        var index = 0
        for (i in 0 until sampleCount) {
            val sample = pcm[i].toInt()
            out[index++] = (sample and 0xFF).toByte()
            out[index++] = ((sample shr 8) and 0xFF).toByte()
        }
        return listOf(out)
    }

    override fun release() = Unit
}

/**
 * Builds the best encoder this device can actually provide for [codecName],
 * falling back rather than failing.
 *
 * Every step down is logged: a session quietly running on PCM when the user
 * chose AAC is a bandwidth surprise, and one quietly running on AAC when the
 * user chose Opus should be visible in a bug report rather than inferred.
 */
fun createAudioEncoder(
    codecName: String,
    sampleRate: Int,
    channels: Int,
    bitrateBps: Int,
): AudioEncoder {
    val requested = codecName.lowercase()
    if (requested != "aac") {
        Log.i(TAG, "audio codec '$codecName' is not implemented for streaming — using AAC-LC instead")
    }
    return try {
        AacAudioEncoder(sampleRate, channels, bitrateBps)
    } catch (e: Exception) {
        Log.w(TAG, "AAC encoder unavailable at ${sampleRate}Hz ${channels}ch — falling back to raw PCM", e)
        PcmAudioEncoder()
    }
}
