package com.framecast.streamer.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs
import kotlin.math.log10

private const val TAG = "AudioLevelMeter"
private const val SAMPLE_RATE = 44100

/**
 * Reads live microphone amplitude and reports two channel levels (0f..1f) for
 * the classic two-bar VU meter. Most phone mics are effectively mono, so this
 * tries stereo capture first (for genuinely separate L/R movement on devices
 * that support it) and falls back to mono — mirroring the same level to both
 * bars — rather than faking a second channel.
 */
class AudioLevelMeter(private val onLevel: (left: Float, right: Float) -> Unit) {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var running = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Returns false if no microphone input could be opened (missing permission, device in use, etc.). */
    fun start(): Boolean {
        if (running) return true

        var stereo = true
        var record = openRecord(AudioFormat.CHANNEL_IN_STEREO)
        if (record == null) {
            stereo = false
            record = openRecord(AudioFormat.CHANNEL_IN_MONO)
        }
        val finalRecord = record ?: return false

        audioRecord = finalRecord
        running = true
        try {
            finalRecord.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "startRecording failed", e)
            finalRecord.release()
            audioRecord = null
            running = false
            return false
        }

        recordingThread = Thread({ recordLoop(finalRecord, stereo) }, "AudioLevelMeter").apply { start() }
        return true
    }

    private fun openRecord(channelConfig: Int): AudioRecord? {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return null
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                null
            } else {
                record
            }
        } catch (e: SecurityException) {
            null // RECORD_AUDIO not granted
        } catch (e: Exception) {
            Log.w(TAG, "openRecord($channelConfig) failed", e)
            null
        }
    }

    private fun recordLoop(record: AudioRecord, stereo: Boolean) {
        val buffer = ShortArray(1024)
        while (running) {
            val read = try {
                record.read(buffer, 0, buffer.size)
            } catch (e: Exception) {
                break
            }
            if (read <= 0) continue

            if (stereo) {
                var sumL = 0.0
                var sumR = 0.0
                var count = 0
                var i = 0
                while (i < read - 1) {
                    sumL += abs(buffer[i].toInt())
                    sumR += abs(buffer[i + 1].toInt())
                    count++
                    i += 2
                }
                val left = amplitudeToLevel(if (count > 0) sumL / count else 0.0)
                val right = amplitudeToLevel(if (count > 0) sumR / count else 0.0)
                mainHandler.post { if (running) onLevel(left, right) }
            } else {
                var sum = 0.0
                for (i in 0 until read) sum += abs(buffer[i].toInt())
                val level = amplitudeToLevel(sum / read)
                mainHandler.post { if (running) onLevel(level, level) }
            }
        }
    }

    /** Rough dBFS mapping for 16-bit PCM: -50dB (near silence) .. 0dB (full scale) -> 0f..1f. */
    private fun amplitudeToLevel(avgAmplitude: Double): Float {
        if (avgAmplitude <= 1.0) return 0f
        val db = 20 * log10(avgAmplitude / 32767.0)
        return (((db + 50) / 50).toFloat()).coerceIn(0f, 1f)
    }

    fun stop() {
        running = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            // already stopped/released
        }
        recordingThread?.let {
            try {
                it.join(200)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        recordingThread = null
        audioRecord?.release()
        audioRecord = null
    }
}
