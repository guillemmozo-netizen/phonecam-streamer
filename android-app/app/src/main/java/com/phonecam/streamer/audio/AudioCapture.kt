package com.phonecam.streamer.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log

private const val TAG = "AudioCapture"

/**
 * Where a device that rejects the requested sample rate ends up. 48kHz is
 * what every Android audio path is built around and what the AAC encoder is
 * happiest with; Settings offers 96kHz, which plenty of microphones simply
 * will not open.
 */
private const val FALLBACK_SAMPLE_RATE = 48000

/**
 * Microphone capture for the *stream* — as opposed to [AudioLevelMeter],
 * which reads the mic only to draw the on-screen VU bars and sends nothing
 * anywhere.
 *
 * Kept separate from the encoder so the two failure modes stay separable: a
 * device with no usable microphone configuration and a device whose AAC
 * encoder won't start are different problems with different fallbacks, and
 * folding them together would make both harder to diagnose from a log.
 *
 * [read] blocks, which is deliberate — AudioRecord's own buffer is the pacing
 * mechanism, and polling it instead would either burn CPU or drop samples.
 * The caller therefore gives this its own thread (see AudioStreamer).
 */
class AudioCapture(private val config: Config) {

    data class Config(
        val sampleRate: Int = 48000,
        val channels: Int = 2,
        /**
         * MediaRecorder.AudioSource.*. VOICE_COMMUNICATION engages the
         * platform's own echo cancellation and is what a webcam wants;
         * MIC is the raw, unprocessed alternative.
         */
        val source: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        val noiseSuppression: Boolean = false,
    )

    private var record: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    /**
     * Channel count actually opened. Not always [Config.channels]: most phone
     * microphones are mono, and a device that cannot open stereo gets one
     * channel rather than a failed stream. The Hello sent to the PC carries
     * *this* number, so the receiver sizes its decoder and output device to
     * what is really being sent.
     */
    var channels: Int = config.channels
        private set

    /**
     * Sample rate actually opened — [Config.sampleRate], or
     * [FALLBACK_SAMPLE_RATE] if this device rejected it. Like [channels],
     * this is what Hello announces, so the PC always decodes at the rate
     * really being sent rather than the one that was asked for.
     */
    var sampleRate: Int = config.sampleRate
        private set

    /** Sample frames per read — around 20ms, matching AAC-LC's frame size closely enough. */
    var frameSamples: Int = 1024
        private set

    /**
     * Opens the microphone. Returns false if it could not be opened at all —
     * missing RECORD_AUDIO, another app holding it exclusively, or a
     * configuration this device rejects. False is a normal outcome the caller
     * handles by streaming video only, not an error to abort the session for.
     */
    fun start(): Boolean {
        if (record != null) return true

        // Widen the search rather than fail: a phone that will not give us
        // stereo at 96kHz will almost always give us mono at 48kHz, and a
        // working microphone in a format we did not ask for beats no
        // microphone at all. Whatever wins is what Hello announces.
        val candidates = LinkedHashSet<Pair<Int, Int>>().apply {
            add(config.sampleRate to config.channels)
            if (config.channels > 1) add(config.sampleRate to 1)
            add(FALLBACK_SAMPLE_RATE to config.channels)
            add(FALLBACK_SAMPLE_RATE to 1)
        }

        var active: AudioRecord? = null
        for ((rate, channelCount) in candidates) {
            active = openRecord(rate, channelCount) ?: continue
            if (rate != config.sampleRate || channelCount != config.channels) {
                Log.i(
                    TAG,
                    "${config.sampleRate}Hz ${config.channels}ch unavailable — captured at ${rate}Hz ${channelCount}ch",
                )
            }
            sampleRate = rate
            channels = channelCount
            break
        }
        if (active == null) {
            Log.w(TAG, "no usable microphone configuration — streaming without audio")
            return false
        }

        return try {
            active.startRecording()
            if (active.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "startRecording() did not take effect — streaming without audio")
                active.release()
                return false
            }
            record = active
            attachEffects(active.audioSessionId)
            Log.i(TAG, "capturing ${sampleRate}Hz ${channels}ch source=${config.source}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "startRecording failed — streaming without audio", e)
            active.release()
            false
        }
    }

    private fun openRecord(rate: Int, channelCount: Int): AudioRecord? {
        val channelMask =
            if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) return null
            // Four times the minimum: this buffer is the only thing absorbing
            // a scheduling delay on the capture thread, and an overrun here is
            // lost audio that no amount of buffering further downstream can
            // recover. It costs a few tens of milliseconds of memory, not of
            // latency — latency is set by how promptly we read, not by how
            // much room there is to read from.
            val record = AudioRecord(
                config.source,
                rate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                null
            } else {
                frameSamples = 1024
                record
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            null
        } catch (e: Exception) {
            Log.w(TAG, "could not open AudioRecord (${channelCount}ch)", e)
            null
        }
    }

    /**
     * Platform noise suppression, when Settings asks for it and this device
     * offers it.
     *
     * Best-effort by design: this is an optional vendor effect and
     * isAvailable() returning false is a normal outcome, not a fault. A
     * missing effect must never cost the user their audio.
     */
    private fun attachEffects(sessionId: Int) {
        if (!config.noiseSuppression) return
        runCatching {
            if (!NoiseSuppressor.isAvailable()) {
                Log.i(TAG, "noise suppression not available on this device")
                return
            }
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        }.onFailure { Log.w(TAG, "could not enable noise suppression", it) }
    }

    /**
     * Blocks until samples are available, filling [into] and returning how
     * many shorts were read (sample frames * channels), 0 on a benign empty
     * read, or -1 once capture has stopped or failed for good.
     */
    fun read(into: ShortArray): Int {
        val active = record ?: return -1
        return try {
            when (val read = active.read(into, 0, into.size)) {
                AudioRecord.ERROR_INVALID_OPERATION, AudioRecord.ERROR_BAD_VALUE, AudioRecord.ERROR_DEAD_OBJECT -> {
                    Log.w(TAG, "AudioRecord.read failed with $read — ending capture")
                    -1
                }
                else -> read
            }
        } catch (e: Exception) {
            // stop() releasing the record while this thread sits inside read()
            // is the ordinary way a session ends, not a fault worth a stack
            // trace at error level.
            Log.i(TAG, "capture read interrupted (${e.javaClass.simpleName}) — ending capture")
            -1
        }
    }

    fun stop() {
        noiseSuppressor?.let { effect -> runCatching { effect.release() } }
        noiseSuppressor = null

        val active = record ?: return
        record = null
        runCatching { active.stop() }
        runCatching { active.release() }
    }
}
