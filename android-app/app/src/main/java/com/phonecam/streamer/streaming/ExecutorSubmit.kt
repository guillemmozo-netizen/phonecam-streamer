package com.phonecam.streamer.streaming

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Submits [task] to [executor], reporting whether it was accepted instead of
 * throwing when the executor is shutting down.
 *
 * ## Why this exists
 *
 * A session's network thread is shut down when streaming stops, but the audio
 * capture thread is a separate thread that keeps running for a few more
 * milliseconds — long enough to hand over one more packet. `execute()` on a
 * shutting-down `ThreadPoolExecutor` does not return an error code, it throws
 * `RejectedExecutionException`, and that exception unwound the capture loop
 * and killed the app:
 *
 *     FATAL EXCEPTION: AudioStreamer
 *     java.util.concurrent.RejectedExecutionException: Task ... rejected from
 *       ThreadPoolExecutor[Shutting down, pool size = 1, active threads = 1,
 *       queued tasks = 1, completed tasks = 3468]
 *       at CameraStreamer.sendAudioPacket
 *       at AudioStreamer.captureLoop
 *
 * Observed on a real device (SM-S918B, Android 16) on every stop of a session
 * with audio enabled. "The executor is closing" is an ordinary, expected race
 * at teardown — not an error worth propagating — so callers get a boolean and
 * decide for themselves.
 */
fun submitIfAccepting(executor: Executor, task: Runnable): Boolean =
    try {
        executor.execute(task)
        true
    } catch (e: RejectedExecutionException) {
        false
    }
