package com.framecast.streamer.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

private const val TAG = "AacEncoder"
private const val DEQUEUE_TIMEOUT_US = 0L

/**
 * Encodes PCM to AAC-LC access units for [com.framecast.streamer.streaming.StreamProtocol].
 *
 * Buffer input, not surface input like the video encoder — audio has no
 * equivalent of a GPU texture path, and at 48 kHz stereo the copy is a few
 * hundred KB/s, far below anything worth optimising.
 *
 * Two things the PC cannot work without and which are easy to get wrong:
 *
 * - The stream is **raw access units, no ADTS headers**. The decoder therefore
 *   cannot infer profile, sample rate or channel layout from the bitstream, so
 *   [codecConfig] (MediaCodec's csd-0, AAC's AudioSpecificConfig) has to reach
 *   it before the first frame. It is delivered through [onConfig] rather than
 *   returned, because MediaCodec emits it asynchronously as a
 *   BUFFER_FLAG_CODEC_CONFIG output whenever it feels like it.
 * - `presentationTimeUs` must advance with the samples actually submitted, not
 *   with wall-clock time. Deriving it from the clock makes the timestamps drift
 *   against the sample count, which the decoder resolves by stretching or
 *   dropping audio — heard as a slow warble rather than as an error.
 */
class AacEncoder(
    private val sampleRate: Int,
    private val channelCount: Int,
    bitrateBps: Int,
    private val onConfig: (ByteArray) -> Unit,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var released = false

    /** AudioSpecificConfig, once MediaCodec has produced it. */
    var codecConfig: ByteArray? = null
        private set

    /** Total PCM frames handed to the codec — the presentation-time clock. */
    private var samplesSubmitted: Long = 0

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            // Room for one AAC frame (1024 samples) per channel at 16-bit, with
            // slack. Undersizing this makes queueInputBuffer reject full reads
            // from AudioCapture, which shows up as periodic dropouts.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * channelCount * 2 * 8)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /**
     * Encodes one buffer of 16-bit PCM and delivers any complete access units.
     *
     * [onFrame]'s ByteArray is freshly allocated per frame and safe to keep or
     * hand to the network thread.
     */
    fun encode(pcm: ByteArray, length: Int, onFrame: (ByteArray) -> Unit) {
        if (released || length <= 0) return

        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex >= 0) {
            val input: ByteBuffer = codec.getInputBuffer(inputIndex) ?: return
            input.clear()
            val writable = minOf(length, input.remaining())
            input.put(pcm, 0, writable)

            val bytesPerFrame = 2 * channelCount
            val presentationTimeUs = samplesSubmitted * 1_000_000L / sampleRate
            codec.queueInputBuffer(inputIndex, 0, writable, presentationTimeUs, 0)
            samplesSubmitted += writable / bytesPerFrame
        } else {
            // Every input buffer busy means the codec is behind. Dropping this
            // read keeps capture running at real time; blocking here would back
            // AudioRecord's own ring buffer up and lose more than one buffer.
            Log.w(TAG, "no input buffer available, dropping ${length}B of PCM")
        }

        drain(onFrame)
    }

    private fun drain(onFrame: (ByteArray) -> Unit) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            if (outputIndex < 0) return

            val output = codec.getOutputBuffer(outputIndex)
            if (output == null) {
                codec.releaseOutputBuffer(outputIndex, false)
                continue
            }
            output.position(bufferInfo.offset)
            output.limit(bufferInfo.offset + bufferInfo.size)

            val bytes = ByteArray(bufferInfo.size)
            output.get(bytes)
            codec.releaseOutputBuffer(outputIndex, false)

            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                // Not audio — the AudioSpecificConfig. Passing it on as a frame
                // would make the decoder try to play its two bytes as samples.
                codecConfig = bytes
                onConfig(bytes)
                continue
            }
            if (bufferInfo.size > 0) onFrame(bytes)
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}
