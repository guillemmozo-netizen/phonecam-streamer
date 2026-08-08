package com.framecast.streamer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log

private const val TAG = "AudioCapture"

/**
 * Captures PCM from a chosen microphone — built-in, wired, USB-C or Bluetooth —
 * and hands it to a callback in the format [AudioPlan] asked for.
 *
 * The routing is the whole point. Android does not let you "open a device": you
 * open the default input and then *ask* to be routed elsewhere, and for
 * Bluetooth you additionally have to bring up a voice link first and wait for
 * it, because the microphone side of a Bluetooth headset does not exist until
 * SCO (or LE Audio) is connected. Getting that wrong doesn't fail loudly — it
 * quietly records the phone's own mic while the user believes they are on their
 * lavalier, which is exactly the class of bug this app already had too much of.
 * So [routedDeviceId] reports what was *actually* routed, and callers are
 * expected to surface it rather than echo the request back.
 */
class AudioCapture(
    private val context: Context,
    private val preferredMic: MicInput?,
    private val plan: AudioPlan,
    private val noiseReductionRequested: Boolean,
    /** What the app is allowed to do; [AudioPermissions.bluetoothLinkUp] is
     *  determined here rather than supplied. */
    private val permissions: AudioPermissions,
) {
    /** Sample rate the AudioRecord was actually created with. */
    var actualSampleRate: Int = plan.sampleRate
        private set

    /** Id of the device frames are really coming from, or null if unknown. */
    var routedDeviceId: Int? = null
        private set

    /** True only if noise suppression was requested *and* this device has it. */
    var noiseReductionActive: Boolean = false
        private set

    private var record: AudioRecord? = null
    private var suppressor: NoiseSuppressor? = null
    private var thread: Thread? = null
    private var scoStarted = false
    @Volatile private var running = false

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Opens the microphone and starts delivering PCM.
     *
     * [onPcm] is called on a dedicated capture thread with a buffer valid only
     * for the duration of the call — copy it if you keep it.
     *
     * Returns null on success, or why it refused. It refuses rather than
     * falling back to the default input: capture that quietly uses a different
     * microphone than the one the user chose is the failure this exists to
     * prevent.
     */
    fun start(onPcm: (buffer: ByteArray, length: Int) -> Unit): CaptureFailure? {
        if (running) return null

        // Two passes on purpose. The permission checks must happen before any
        // SCO call, since those are what throw without them; whether the link
        // came up can only be known after trying. Passing bluetoothLinkUp=true
        // here asks the gate the permission question alone.
        (AudioCaptureGate.beforeStart(preferredMic, permissions.copy(bluetoothLinkUp = true))
            as? CaptureDecision.Refuse)?.let { return it.failure }

        if (preferredMic?.kind == MicKind.BLUETOOTH_SCO) {
            val linkUp = startBluetoothSco()
            (AudioCaptureGate.beforeStart(preferredMic, permissions.copy(bluetoothLinkUp = linkUp))
                as? CaptureDecision.Refuse)?.let {
                stopBluetoothSco()
                return it.failure
            }
        }

        val channelMask =
            if (plan.channelCount >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuffer = AudioRecord.getMinBufferSize(
            plan.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "no buffer size for ${plan.sampleRate}Hz/${plan.channelCount}ch")
            stopBluetoothSco()
            return CaptureFailure.MIC_UNAVAILABLE
        }
        // Four times the minimum: the encoder runs on this same thread, and a
        // buffer sized to the bare minimum overruns (dropping audio, audible as
        // clicks) whenever the codec takes longer than one buffer to accept a
        // chunk. Latency cost is a few milliseconds.
        val bufferBytes = minBuffer * 4

        val opened = try {
            AudioRecord.Builder()
                // VOICE_RECOGNITION rather than MIC: it is the source Android
                // documents as having platform processing (AGC, and the very
                // noise suppression this class then controls explicitly) left
                // off by default, so what gets encoded is what the microphone
                // heard rather than something already tuned for a phone call.
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(plan.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: Exception) {
            // SecurityException (permission), IllegalArgumentException (format),
            // UnsupportedOperationException (device busy) — all mean the same
            // thing to the caller.
            Log.w(TAG, "could not open AudioRecord", e)
            stopBluetoothSco()
            return CaptureFailure.MIC_UNAVAILABLE
        }

        if (opened.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord did not initialise (state=${opened.state})")
            opened.release()
            stopBluetoothSco()
            return CaptureFailure.MIC_UNAVAILABLE
        }

        routeTo(opened, preferredMic)
        enableNoiseReduction(opened)

        actualSampleRate = opened.sampleRate
        if (actualSampleRate != plan.sampleRate) {
            Log.i(TAG, "asked for ${plan.sampleRate}Hz, got ${actualSampleRate}Hz")
        }

        record = opened
        running = true
        opened.startRecording()
        // Routing only resolves once recording has started — before that,
        // getRoutedDevice() returns null on most devices.
        routedDeviceId = opened.routedDevice?.id

        // The check this whole gate exists for: every call succeeded and the
        // framework handed us a different microphone anyway.
        (AudioCaptureGate.afterStart(preferredMic, routedDeviceId) as? CaptureDecision.Refuse)?.let {
            Log.w(TAG, "routed to device $routedDeviceId, not ${preferredMic?.id}; refusing")
            stop()
            return it.failure
        }

        thread = Thread({
            val buffer = ByteArray(bufferBytes)
            while (running) {
                val read = opened.read(buffer, 0, buffer.size)
                if (read > 0) {
                    onPcm(buffer, read)
                } else if (read < 0) {
                    // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT: the device
                    // went away (headset unplugged, Bluetooth dropped). Ending
                    // the loop lets the caller notice and re-plan rather than
                    // spinning on a dead record.
                    Log.w(TAG, "read error $read, ending capture")
                    break
                }
            }
        }, "framecast-audio-capture").also { it.start() }

        return null
    }

    fun stop() {
        running = false
        thread?.join(1_000)
        thread = null

        suppressor?.runCatching { release() }
        suppressor = null

        record?.let { r ->
            runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
            runCatching { r.release() }
        }
        record = null
        stopBluetoothSco()
    }

    private fun routeTo(record: AudioRecord, mic: MicInput?) {
        if (mic == null) return
        val target = audioManager
            ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.id == mic.id }
        if (target == null) {
            Log.w(TAG, "requested mic ${mic.id} (${mic.productName}) is gone; using the default")
            return
        }
        if (!record.setPreferredDevice(target)) {
            Log.w(TAG, "setPreferredDevice refused ${mic.productName}; using the default")
        }
    }

    /**
     * Turns on the platform noise suppressor when one exists.
     *
     * This is what the Settings toggle now actually does. [NoiseSuppressor] is
     * an optional effect — plenty of devices have none — so
     * [noiseReductionActive] is the honest answer to "is it on", as opposed to
     * the preference, which is only what was asked for.
     */
    private fun enableNoiseReduction(record: AudioRecord) {
        if (!noiseReductionRequested) return
        if (!NoiseSuppressor.isAvailable()) {
            Log.i(TAG, "noise suppression requested but this device has none")
            return
        }
        suppressor = runCatching {
            NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        }.getOrNull()
        noiseReductionActive = suppressor?.enabled == true
        if (!noiseReductionActive) Log.w(TAG, "noise suppressor could not be enabled")
    }

    /**
     * Brings up the Bluetooth voice link, without which a paired headset's
     * microphone simply is not among the input devices.
     *
     * The wait is not optional: SCO takes a moment to connect and an
     * AudioRecord opened before it is up gets routed to the built-in mic and
     * stays there for the whole session.
     */
    private fun startBluetoothSco(): Boolean {
        val manager = audioManager ?: return false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val device = manager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (device != null && manager.setCommunicationDevice(device)) {
                    scoStarted = true
                }
            } else {
                @Suppress("DEPRECATION")
                manager.startBluetoothSco()
                @Suppress("DEPRECATION")
                manager.setBluetoothScoOn(true)
                scoStarted = true
            }
        }.onFailure { Log.w(TAG, "could not start the Bluetooth voice link", it) }

        if (!scoStarted) return false
        // Poll rather than listen for ACTION_SCO_AUDIO_STATE_UPDATED: this runs
        // off the main thread already, and a receiver would need registering,
        // unregistering and its own timeout for the same answer.
        val deadline = System.currentTimeMillis() + SCO_CONNECT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val ready = audioManager
                ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                ?.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } == true
            if (ready) return true
            Thread.sleep(SCO_POLL_INTERVAL_MS)
        }
        Log.w(TAG, "Bluetooth voice link did not come up in ${SCO_CONNECT_TIMEOUT_MS}ms")
        return false
    }

    private fun stopBluetoothSco() {
        if (!scoStarted) return
        scoStarted = false
        val manager = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                manager.setBluetoothScoOn(false)
                @Suppress("DEPRECATION")
                manager.stopBluetoothSco()
            }
        }.onFailure { Log.w(TAG, "could not tear down the Bluetooth voice link", it) }
    }

    private companion object {
        const val SCO_CONNECT_TIMEOUT_MS = 3_000L
        const val SCO_POLL_INTERVAL_MS = 100L
    }
}
