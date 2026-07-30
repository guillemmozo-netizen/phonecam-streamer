package com.phonecam.streamer.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.phonecam.streamer.streaming.gl.EncoderSurfaceRenderer

private const val TAG = "H264Encoder"
private const val I_FRAME_INTERVAL_SECONDS = 2

/**
 * One encoded chunk out of the video encoder.
 *
 * [presentationTimeUs] is the codec's own timestamp for this data, not the
 * time it happened to be drained. The two are not the same: MediaCodec
 * pipelines a frame or two deep, so stamping chunks on the way out would
 * attribute one frame's timing to another's picture — visible as jitter in
 * the PC's A/V alignment, which is measured in exactly these units (see
 * pc_receiver/av_sync.py).
 *
 * [isConfig] marks the SPS/PPS chunk, which is data *about* the stream
 * rather than a picture in it — see [H264Encoder.codecConfig].
 */
data class EncodedChunk(
    val presentationTimeUs: Long,
    val data: ByteArray,
    val isConfig: Boolean,
) {
    // data class equality on a ByteArray compares references, which is
    // never what a caller means. Overridden so tests and any future set/map
    // use compare contents.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedChunk) return false
        return presentationTimeUs == other.presentationTimeUs &&
            isConfig == other.isConfig &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int =
        (presentationTimeUs.hashCode() * 31 + data.contentHashCode()) * 31 + isConfig.hashCode()
}

/**
 * Above 4K, AVC stops being a sensible choice: this device's AVC encoder
 * nominally accepts 8K (`c2.qti.avc.encoder`, max 8192x8192) but AVC level 6
 * support for it is far less dependable than HEVC's, and HEVC halves the
 * bitrate for the same quality — which matters when the transport is a USB
 * tunnel. Measured on-device: `c2.qti.hevc.encoder` reports
 * `sizeSupported(7680x4320)=true` and `areSizeAndRateSupported(...,30)=true`,
 * and encoded a real 8K30 camera stream at a clean 30.0fps.
 */
fun mimeTypeFor(width: Int, height: Int): String =
    if (width.toLong() * height > 3840L * 2160) MediaFormat.MIMETYPE_VIDEO_HEVC
    else MediaFormat.MIMETYPE_VIDEO_AVC
private const val DEQUEUE_TIMEOUT_US = 0L // never block the camera thread waiting on the codec

/**
 * Wraps MediaCodec's H.264 encoder in Surface-input mode: the camera writes
 * frames directly into [renderer]'s [EncoderSurfaceRenderer.cameraInputSurface]
 * as a GPU texture (see that class's doc for why — no CPU buffer copy,
 * unlike the old ImageAnalysis-based path), and [renderer] draws each one
 * (OES-corrected, rotated, watermarked) into this codec's input surface.
 *
 * Output draining (dequeueOutputBuffer / Annex-B chunk extraction) is
 * unchanged from the original buffer-mode version — Surface input only
 * replaces how frames get IN, not how encoded data comes OUT.
 *
 * [width]/[height]/[fps]/[bitrateBps] are fixed for the lifetime of this
 * instance — a real encoder can't change its output size on the fly, so
 * CameraStreamer creates one of these per streaming session (see
 * effectiveTarget's doc: a mid-stream reward-tier change just keeps that
 * session at whatever it started with).
 *
 * Not thread-safe: [renderFrame]/[drainOutput] must always be called from
 * the same thread (CameraStreamer's dedicated GL thread, under
 * encoderLock — see CameraStreamer.stop()'s doc for why that lock exists).
 */
class H264Encoder(
    width: Int,
    height: Int,
    fps: Int,
    bitrateBps: Int,
    // Name is historical: this now drives H.265 too (see mimeTypeFor). A rename
    // to VideoEncoder is pending and purely cosmetic.
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(mimeType)
    private val bufferInfo = MediaCodec.BufferInfo()
    val renderer: EncoderSurfaceRenderer
    private var released = false

    /** What to put in Hello.codec so the PC picks the matching decoder. */
    val codecName: String =
        if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) "h265" else "h264"

    /**
     * The SPS/PPS chunk, kept from when the encoder first emitted it.
     *
     * Held because a session outlives its connections. The encoder produces
     * this once, at start; every reconnect (cable pulled, PC asleep, receiver
     * restarted) gives the PC a brand-new decoder that has never seen it, and
     * an H.264 decoder without SPS/PPS decodes nothing at all. Before this
     * was kept, a reconnect produced a stream that was still arriving and
     * still being decoded — into no frames whatsoever, indistinguishable from
     * a black camera.
     *
     * Re-sending it is only half the fix; the other half is
     * [requestKeyFrame], since a decoder joining mid-GOP has nothing to
     * anchor to even with the config in hand.
     */
    @Volatile
    var codecConfig: EncodedChunk? = null
        private set

    init {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }
        // Not every encoder advertises CBR support (some throw configuring
        // it) — a live network stream wants steady, predictable bandwidth
        // more than VBR's better per-frame quality, but it's a nice-to-have,
        // not something to fail streaming over.
        try {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "CBR bitrate mode not accepted, falling back to encoder default", e)
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val inputSurface = codec.createInputSurface()
        codec.start()
        renderer = EncoderSurfaceRenderer(inputSurface, width, height)
    }

    /**
     * Renders whatever camera frame [renderer] currently has latched (see
     * [EncoderSurfaceRenderer.renderCameraFrame]) into the encoder's input
     * surface and returns whatever encoded Annex-B chunks are ready now.
     *
     * Usually 0 or 1 chunk per call. The very first call typically also
     * yields a separate codec-config chunk (SPS/PPS, BUFFER_FLAG_CODEC_CONFIG)
     * ahead of the first real frame — sent over the wire exactly like any
     * other chunk; the PC-side H264Decoder doesn't need to know the
     * difference, FFmpeg's Annex-B parser handles it either concatenated or
     * as its own message.
     */
    fun encode(rotationDegrees: Int, watermark: Boolean, presentationTimeUs: Long): List<EncodedChunk> {
        if (released) return emptyList()
        renderer.renderCameraFrame(rotationDegrees, watermark, presentationTimeUs)
        return drainOutput()
    }

    /**
     * Asks the encoder to make the next frame a keyframe.
     *
     * Called on reconnect. A decoder that joins the stream part-way through a
     * GOP cannot produce a picture until the next IDR, and at
     * [I_FRAME_INTERVAL_SECONDS] apart that is up to two seconds of black
     * after every reconnect. Requesting one costs a single larger frame.
     *
     * Safe to call from another thread — MediaCodec.setParameters is
     * documented as callable at any time on a running codec — but not after
     * [release], hence the flag check.
     */
    fun requestKeyFrame() {
        if (released) return
        try {
            codec.setParameters(
                android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                },
            )
        } catch (e: Exception) {
            // Not fatal: the periodic IDR still arrives, just later.
            Log.w(TAG, "could not request a keyframe", e)
        }
    }

    private fun drainOutput(): List<EncodedChunk> {
        val chunks = mutableListOf<EncodedChunk>()
        drainOutput@ while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break@drainOutput
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue@drainOutput
                outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue@drainOutput
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.get(data)
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val chunk = EncodedChunk(bufferInfo.presentationTimeUs, data, isConfig)
                        if (isConfig) codecConfig = chunk
                        chunks.add(chunk)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
                else -> break@drainOutput
            }
        }
        return chunks
    }

    fun release() {
        if (released) return
        released = true
        try {
            renderer.release()
        } catch (e: Exception) {
            Log.w(TAG, "renderer.release() failed", e)
        }
        try {
            codec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "codec.stop() failed", e)
        }
        try {
            codec.release()
        } catch (e: Exception) {
            Log.w(TAG, "codec.release() failed", e)
        }
    }
}
