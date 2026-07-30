package com.phonecam.streamer.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Regression tests for a crash found on real hardware (SM-S918B, Android 16).
 *
 * Stopping a session with audio enabled killed the app every time:
 *
 *     FATAL EXCEPTION: AudioStreamer
 *     java.util.concurrent.RejectedExecutionException: Task ... rejected from
 *       ThreadPoolExecutor[Shutting down, ...]
 *       at CameraStreamer.sendAudioPacket
 *       at AudioStreamer.captureLoop
 *
 * The audio capture thread outlives the network thread by a few milliseconds
 * at teardown, so it hands over one more packet to an executor that is already
 * shutting down. `execute()` throws rather than returning a status, and the
 * exception unwound the capture loop.
 */
class ExecutorSubmitTest {

    @Test
    fun `a live executor accepts the task and runs it`() {
        val executor = Executors.newSingleThreadExecutor()
        val ran = CountDownLatch(1)

        val accepted = submitIfAccepting(executor) { ran.countDown() }

        assertTrue(accepted)
        assertTrue("the task should have run", ran.await(2, TimeUnit.SECONDS))
        executor.shutdown()
    }

    @Test
    fun `a shut-down executor reports rejection instead of throwing`() {
        val executor = Executors.newSingleThreadExecutor()
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))

        // The exact condition that crashed the app.
        val accepted = submitIfAccepting(executor) { fail("must not run") }

        assertFalse(accepted)
    }

    @Test
    fun `rejection while tasks are still draining does not throw`() {
        // The real shape of the race: shutdown() called while a long task is
        // still running, so the executor is "Shutting down" rather than
        // "Terminated" — which is precisely the state named in the crash.
        val executor = Executors.newSingleThreadExecutor()
        val blocking = CountDownLatch(1)
        executor.execute { blocking.await(2, TimeUnit.SECONDS) }
        executor.shutdown()

        val accepted = submitIfAccepting(executor) { }

        assertFalse("a shutting-down executor must reject, not throw", accepted)
        blocking.countDown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    @Test
    fun `many rejected submissions in a row stay silent`() {
        // The capture thread emits ~47 packets a second; at teardown several
        // can land after shutdown. Every one must be survivable.
        val executor = Executors.newSingleThreadExecutor()
        executor.shutdown()

        var rejected = 0
        repeat(100) { if (!submitIfAccepting(executor) { }) rejected++ }

        assertEquals(100, rejected)
    }

    @Test(expected = RejectedExecutionException::class)
    fun `raw execute really does throw — the behaviour being guarded against`() {
        // Pins the premise. If a future JDK made execute() lenient, the guard
        // would be pointless and this test would say so.
        val executor = Executors.newSingleThreadExecutor()
        executor.shutdown()
        executor.execute { }
    }

    @Test
    fun `an exception thrown by the task itself is not swallowed as rejection`() {
        // submitIfAccepting reports acceptance, not task success. A task that
        // throws once running is the executor's problem, not a rejection —
        // conflating them would hide real failures.
        val executor = Executors.newSingleThreadExecutor()

        val accepted = submitIfAccepting(executor) { throw IllegalStateException("boom") }

        assertTrue("submission was accepted even though the task later failed", accepted)
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
