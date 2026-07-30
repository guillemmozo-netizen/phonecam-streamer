package com.phonecam.streamer.audio

/**
 * Turns "how many samples have been captured so far" into presentation
 * timestamps on the same clock video uses.
 *
 * ## Why counted, not read off the clock
 *
 * The obvious implementation — stamp each buffer with System.nanoTime() when
 * AudioRecord.read() returns — produces timestamps as jittery as the thread
 * scheduling that produced them, and the jitter lands straight in the PC's
 * A/V alignment. Sample counting instead produces an exactly even timeline,
 * because that is what the audio really is: a continuous stream at a fixed
 * rate, whatever the delivery of it looks like.
 *
 * The anchor is taken from System.nanoTime() — the same clock
 * CameraStreamer stamps video frames with — which is the whole point. The
 * two streams are only alignable on the PC (see pc_receiver/av_sync.py)
 * because their timestamps come from one clock; the sample count supplies
 * the spacing, the anchor supplies the shared origin.
 *
 * ## The drift this deliberately does not correct
 *
 * The microphone's real rate is never exactly its nominal one, so over a
 * long session this timeline and nanoTime pull apart by a few samples a
 * minute. Correcting it here would mean resampling; instead the PC's playout
 * buffer absorbs it by dropping a few milliseconds when its queue grows
 * (pc_receiver/audio_sinks.PlayoutBuffer), which is inaudible and needs no
 * cooperation from this side.
 *
 * Pure Kotlin, no Android types: the arithmetic here decides whether audio
 * and video line up at all, so it is unit tested rather than inferred from
 * how a stream sounds.
 */
class AudioTimeline(private val sampleRate: Int) {

    init {
        require(sampleRate > 0) { "sampleRate must be positive, got $sampleRate" }
    }

    private var anchorNanos = 0L
    private var started = false

    /** Total sample frames handed out so far — the audio's own position. */
    var samplesCaptured: Long = 0L
        private set

    /**
     * Anchors the timeline to [nowNanos], which must come from
     * System.nanoTime() so that it shares an origin with video's timestamps.
     * Called once, when the first buffer arrives rather than when capture is
     * requested: the gap between AudioRecord.startRecording() returning and
     * real samples appearing is device-dependent, and anchoring at the wrong
     * end of it offsets the entire session's lip-sync.
     */
    fun start(nowNanos: Long) {
        anchorNanos = nowNanos
        samplesCaptured = 0L
        started = true
    }

    val isStarted: Boolean get() = started

    /**
     * Timestamp, in microseconds, of the *next* sample to be captured — i.e.
     * of a buffer about to be recorded. Advance with [advance] once its real
     * length is known.
     */
    fun nextPtsUs(): Long = anchorNanos / 1_000 + samplesCaptured * 1_000_000 / sampleRate

    /** Records that [sampleFrames] more frames have been captured. */
    fun advance(sampleFrames: Int) {
        require(sampleFrames >= 0) { "sampleFrames must not be negative, got $sampleFrames" }
        samplesCaptured += sampleFrames
    }

    /** Seconds of audio captured so far — for logging and diagnostics. */
    fun capturedSeconds(): Double = samplesCaptured.toDouble() / sampleRate
}
