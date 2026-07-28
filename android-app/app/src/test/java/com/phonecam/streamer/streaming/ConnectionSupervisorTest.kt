package com.phonecam.streamer.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconnection policy, exercised without sockets or real delays.
 *
 * Every case here maps to something that actually happens to a USB session:
 * the cable coming out, `adb kill-server`, the receiver restarting, the PC
 * sleeping, or the user stopping the stream in the middle of a retry.
 */
class ConnectionSupervisorTest {

    private class FakeConn(val id: Int) {
        var closed = false
    }

    private fun supervisor(
        connect: () -> FakeConn,
        maxAttempts: Int = 0,
        onStateChange: (ConnectionState) -> Unit = {},
    ): ConnectionSupervisor<FakeConn> {
        val slept = mutableListOf<Long>()
        return ConnectionSupervisor(
            connect = connect,
            closer = { it.closed = true },
            sleeper = { slept.add(it) },   // no real waiting
            maxAttempts = maxAttempts,
            onStateChange = onStateChange,
        )
    }

    private val never = { false }

    // ---------- first connect ----------

    @Test
    fun connectsOnFirstAttempt() {
        val s = supervisor({ FakeConn(1) })
        assertNotNull(s.ensureConnected(never))
        assertEquals(ConnectionState.CONNECTED, s.state)
        assertEquals(0, s.attempts)
    }

    @Test
    fun retriesUntilThePcAppears() {
        var n = 0
        val s = supervisor({
            n++
            if (n < 4) throw RuntimeException("connection refused")
            FakeConn(n)
        })
        assertNotNull(s.ensureConnected(never))
        assertEquals(ConnectionState.CONNECTED, s.state)
        assertEquals(4, n)
    }

    @Test
    fun reusesTheLiveConnectionInsteadOfDialling() {
        var n = 0
        val s = supervisor({ n++; FakeConn(n) })
        val first = s.ensureConnected(never)
        val second = s.ensureConnected(never)
        assertSame(first, second)
        assertEquals(1, n)
    }

    // ---------- loss and recovery ----------

    @Test
    fun reconnectsAfterTheCableIsPulled() {
        var n = 0
        val s = supervisor({ n++; FakeConn(n) })
        val first = s.ensureConnected(never)!!
        s.markLost()
        assertEquals(ConnectionState.RECONNECTING, s.state)
        val second = s.ensureConnected(never)!!
        assertTrue(first !== second)
        assertEquals(ConnectionState.CONNECTED, s.state)
    }

    @Test
    fun losingTheConnectionClosesTheOldSocket() {
        val conn = FakeConn(1)
        val s = supervisor({ conn })
        s.ensureConnected(never)
        s.markLost()
        assertTrue(conn.closed)
    }

    @Test
    fun markLostIsIdempotentAcrossABurstOfSendFailures() {
        /** Every queued frame fails at once; none may close a newer socket. */
        var n = 0
        val s = supervisor({ n++; FakeConn(n) })
        s.ensureConnected(never)
        s.markLost()
        s.markLost()
        s.markLost()
        val fresh = s.ensureConnected(never)!!
        assertEquals(2, fresh.id)
        assertTrue(!fresh.closed)
    }

    @Test
    fun survivesAdbRestartWhichRefusesForAWhileThenWorks() {
        var n = 0
        val s = supervisor({
            n++
            if (n in 2..6) throw RuntimeException("adb tunnel down")
            FakeConn(n)
        })
        s.ensureConnected(never)
        s.markLost()
        assertNotNull(s.ensureConnected(never))
        assertEquals(ConnectionState.CONNECTED, s.state)
    }

    @Test
    fun recoversRepeatedlyAcrossManyDropouts() {
        var n = 0
        val s = supervisor({ n++; FakeConn(n) })
        repeat(25) {
            assertNotNull(s.ensureConnected(never))
            s.markLost()
        }
        assertNotNull(s.ensureConnected(never))
        assertEquals(ConnectionState.CONNECTED, s.state)
    }

    // ---------- backoff ----------

    @Test
    fun backoffGrowsWhileFailingAndIsCapped() {
        val s = ConnectionSupervisor<FakeConn>(
            connect = { throw RuntimeException("no") },
            sleeper = {},
            maxAttempts = 12,
            initialBackoffMs = 500,
            maxBackoffMs = 8_000,
        )
        s.ensureConnected(never)
        assertEquals(8_000L, s.currentBackoffMs())
    }

    @Test
    fun backoffResetsAfterASuccessfulReconnect() {
        var n = 0
        val s = ConnectionSupervisor<FakeConn>(
            connect = {
                n++
                if (n <= 5) throw RuntimeException("no")
                FakeConn(n)
            },
            sleeper = {},
            initialBackoffMs = 500,
            maxBackoffMs = 8_000,
        )
        s.ensureConnected(never)
        assertEquals(500L, s.currentBackoffMs())
    }

    @Test
    fun firstRetryIsFastRatherThanBackedOff() {
        /** A USB tunnel usually returns within a second; backing off immediately
         * would make the common case feel broken. */
        val waits = mutableListOf<Long>()
        var n = 0
        // One failure, one wait: the sleep is deliberately chopped into short
        // steps so a stop request is honoured mid-wait, so assert on the total
        // rather than on any single call.
        ConnectionSupervisor<FakeConn>(
            connect = { n++; if (n < 2) throw RuntimeException("no") else FakeConn(n) },
            sleeper = { waits.add(it) },
            initialBackoffMs = 500,
        ).ensureConnected(never)
        assertEquals(500L, waits.sum())
    }

    // ---------- giving up / stopping ----------

    @Test
    fun givesUpOnlyWhenAttemptsAreBounded() {
        val s = supervisor({ throw RuntimeException("no") }, maxAttempts = 3)
        assertNull(s.ensureConnected(never))
        assertEquals(ConnectionState.GIVEN_UP, s.state)
    }

    @Test
    fun unlimitedModeKeepsTryingAndNeverGivesUp() {
        var n = 0
        val s = supervisor({
            n++
            if (n < 50) throw RuntimeException("no") else FakeConn(n)
        })
        assertNotNull(s.ensureConnected(never))
        assertTrue(s.state != ConnectionState.GIVEN_UP)
    }

    @Test
    fun stoppingDuringRetriesReturnsPromptly() {
        var n = 0
        val s = supervisor({ n++; throw RuntimeException("no") })
        assertNull(s.ensureConnected { n >= 3 })
        assertTrue("should stop soon after the flag flips, got $n", n <= 4)
    }

    @Test
    fun stoppingDuringTheBackoffWaitIsHonoured() {
        var stop = false
        val waits = mutableListOf<Long>()
        val s = ConnectionSupervisor<FakeConn>(
            connect = { throw RuntimeException("no") },
            sleeper = { waits.add(it); stop = true },
            initialBackoffMs = 5_000,
        )
        s.ensureConnected { stop }
        assertTrue("must not sleep the whole backoff in one go", waits.all { it <= 100 })
    }

    // ---------- shutdown ----------

    @Test
    fun shutdownClosesAndDoesNotArmAReconnect() {
        val conn = FakeConn(1)
        val s = supervisor({ conn })
        s.ensureConnected(never)
        s.shutdown()
        assertTrue(conn.closed)
        assertEquals(ConnectionState.IDLE, s.state)
        assertNull(s.connection)
    }

    @Test
    fun shutdownWithoutAConnectionIsSafe() {
        supervisor({ FakeConn(1) }).shutdown()
    }

    @Test
    fun closerThrowingDoesNotPropagate() {
        /** Closing an already-broken socket routinely throws. */
        val s = ConnectionSupervisor<FakeConn>(
            connect = { FakeConn(1) },
            closer = { throw RuntimeException("already closed") },
            sleeper = {},
        )
        s.ensureConnected(never)
        s.markLost()
        assertEquals(ConnectionState.RECONNECTING, s.state)
    }

    // ---------- observable state ----------

    @Test
    fun reportsTheStateSequenceForADropAndRecovery() {
        val seen = mutableListOf<ConnectionState>()
        var n = 0
        val s = supervisor({ n++; FakeConn(n) }, onStateChange = { seen.add(it) })
        s.ensureConnected(never)
        s.markLost()
        s.ensureConnected(never)
        assertEquals(
            listOf(
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING,
                ConnectionState.CONNECTED,
            ),
            seen,
        )
    }

    @Test
    fun doesNotEmitDuplicateStates() {
        val seen = mutableListOf<ConnectionState>()
        val s = supervisor({ FakeConn(1) }, onStateChange = { seen.add(it) })
        s.ensureConnected(never)
        s.ensureConnected(never)
        assertEquals(1, seen.count { it == ConnectionState.CONNECTED })
    }
}
