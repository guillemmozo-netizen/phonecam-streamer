package com.framecast.streamer.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.framecast.streamer.streaming.gl.EncoderSurfaceRenderer

private const val TAG = "H264Encoder"
private const val I_FRAME_INTERVAL_SECONDS = 2

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
    fun encode(rotationDegrees: Int, watermark: Boolean, presentationTimeUs: Long): List<ByteArray> {
        if (released) return emptyList()
        renderer.renderCameraFrame(rotationDegrees, watermark, presentationTimeUs)
        return drainOutput()
    }

    private fun drainOutput(): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        drainOutput@ while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break@drainOutput
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue@drainOutput
                outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue@drainOutput
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.get(chunk)
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
