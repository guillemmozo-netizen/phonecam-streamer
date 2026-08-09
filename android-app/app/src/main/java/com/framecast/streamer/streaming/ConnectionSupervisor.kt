package com.framecast.streamer.streaming

/**
 * Owns "stay connected to the PC" for a streaming session.
 *
 * The previous logic only retried the *initial* connect (5 attempts, fixed 2s
 * apart) and then gave up for good. Anything that dropped the socket mid-stream
 * — unplugging USB, `adb kill-server`, the PC sleeping, the receiver restarting
 * — ended the session silently: frames kept being encoded and thrown away
 * because `connection` was null, with no attempt to come back.
 *
 * Deliberately free of Android and socket types so the whole policy is unit
 * tested on the JVM: connector, closer, clock and sleeper are all injected.
 * Timing-dependent behaviour (backoff growth, caps, resets) is then verified
 * without real delays.
 */
enum class ConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    /** Was connected, lost it, trying to get back. */
    RECONNECTING,
    /** Attempt budget exhausted — only reachable with a finite maxAttempts. */
    GIVEN_UP,
}

class ConnectionSupervisor<T : Any>(
    private val connect: () -> T,
    private val closer: (T) -> Unit = {},
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    /** 0 means unlimited — the auto-reconnect case, where giving up is worse than waiting. */
    private val maxAttempts: Int = 0,
    private val initialBackoffMs: Long = 500,
    private val maxBackoffMs: Long = 8_000,
    private val onStateChange: (ConnectionState) -> Unit = {},
) {
    @Volatile
    var state: ConnectionState = ConnectionState.IDLE
        private set

    @Volatile
    var connection: T? = null
        private set

    /** Attempts made since the last successful connect — reset on success. */
    @Volatile
    var attempts: Int = 0
        private set

    private var backoffMs: Long = initialBackoffMs
    private val lock = Any()

    /**
     * Blocks until connected, the attempt budget runs out, or [stopped] goes
     * true. Returns the live connection, or null if it could not get one.
     *
     * [stopped] is polled rather than taken once so a user stopping the stream
     * during a backoff wait is honoured promptly instead of after the full
     * delay.
     */
    fun ensureConnected(stopped: () -> Boolean): T? {
        synchronized(lock) {
            connection?.let { return it }
        }
        transition(if (state == ConnectionState.IDLE) ConnectionState.CONNECTING else ConnectionState.RECONNECTING)

        while (!stopped()) {
            if (maxAttempts > 0 && attempts >= maxAttempts) {
                transition(ConnectionState.GIVEN_UP)
                return null
            }
            attempts++
            try {
                val fresh = connect()
                synchronized(lock) { connection = fresh }
                backoffMs = initialBackoffMs
                attempts = 0
                transition(ConnectionState.CONNECTED)
                return fresh
            } catch (e: Exception) {
                if (maxAttempts > 0 && attempts >= maxAttempts) {
                    transition(ConnectionState.GIVEN_UP)
                    return null
                }
                sleepInterruptibly(stopped)
                // Grow only after actually waiting, so the first retry is fast
                // (a USB tunnel usually returns within a second) and only a
                // genuinely absent PC backs off towards the ceiling.
                backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
            }
        }
        return null
    }

    /**
     * Marks the current connection as dead, closing it. The next
     * [ensureConnected] starts a reconnect from a fresh backoff.
     *
     * Idempotent: send failures arrive in bursts (every queued frame fails at
     * once), and each one calling this must not close a *newer* connection that
     * a concurrent reconnect already established.
     */
    fun markLost() {
        val doomed = synchronized(lock) {
            val current = connection ?: return
            connection = null
            current
        }
        try {
            closer(doomed)
        } catch (e: Exception) {
            // Closing a socket that is already broken routinely throws; the
            // point was to release it, and it is released.
        }
        backoffMs = initialBackoffMs
        attempts = 0
        transition(ConnectionState.RECONNECTING)
    }

    /** Releases the connection without arming a reconnect — session over. */
    fun shutdown() {
        val doomed = synchronized(lock) {
            val current = connection
            connection = null
            current
        }
        if (doomed != null) {
            try {
                closer(doomed)
            } catch (e: Exception) {
            }
        }
        transition(ConnectionState.IDLE)
    }

    /** Current backoff, exposed for diagnostics and tests. */
    fun currentBackoffMs(): Long = backoffMs

    private fun sleepInterruptibly(stopped: () -> Boolean) {
        var waited = 0L
        val step = 100L
        while (waited < backoffMs && !stopped()) {
            sleeper(minOf(step, backoffMs - waited))
            waited += step
        }
    }

    private fun transition(next: ConnectionState) {
        if (state == next) return
        state = next
        onStateChange(next)
    }
}
