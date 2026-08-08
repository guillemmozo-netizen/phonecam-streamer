package com.framecast.streamer.network

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object PcControl {
    private const val PORT = 8790
    private const val TIMEOUT = 4000

    data class Status(val running: Boolean, val services: List<String>)

    fun getStatus(host: String): Status? {
        return try {
            val conn = URL("http://$host:$PORT/status").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val services = json.getJSONArray("services")
            Status(
                running = json.getBoolean("running"),
                services = (0 until services.length()).map { services.getString(it) }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun startServices(host: String): Boolean {
        return post(host, "/start")
    }

    fun stopServices(host: String): Boolean {
        return post(host, "/stop")
    }

    fun setupAdbReverse(host: String): Boolean {
        return post(host, "/adb-reverse")
    }

    /**
     * Pushes the current settings into OBS immediately, instead of waiting for
     * the next stream to start.
     *
     * Syncing only on Hello meant a settings change did nothing visible until
     * the next recording — and by then OBS has an active output, which is
     * exactly when it rejects SetVideoSettings. Doing it from Settings, while
     * nothing is running, is the moment it can actually be applied.
     *
     * Fire-and-forget: this is a convenience, never a reason to block or fail
     * saving a setting, so callers run it off the main thread and ignore the
     * result.
     */
    fun syncObs(
        host: String,
        width: Int,
        height: Int,
        fps: Int,
        videoBitrateBps: Int,
        audioBitrateBps: Int,
        sampleRate: Int,
    ): Boolean {
        val body = JSONObject().apply {
            put("width", width)
            put("height", height)
            put("fps", fps)
            put("video_bitrate_bps", videoBitrateBps)
            put("audio_bitrate_bps", audioBitrateBps)
            put("sample_rate", sampleRate)
        }.toString()
        return try {
            val conn = URL("http://$host:$PORT/obs-sync").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pairing: fetches the PC's control token over the USB tunnel and returns
     * it, so a later Wi-Fi session can authenticate.
     *
     * The PC only serves this on loopback, which `adb reverse` satisfies -
     * that path already required physical access and an authorised adb key,
     * so it is the natural place to hand over a secret without asking the
     * user to type anything.
     */
    fun fetchToken(host: String): String? {
        return try {
            val conn = URL("http://$host:$PORT/token").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(body).optString("token").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Wi-Fi pairing: asks the PC for its token over the LAN.
     *
     * Unlike [fetchToken] this reaches the PC on its real address rather than
     * through the USB tunnel, which is the point — a phone that has never been
     * plugged in has no other way to obtain the token, and without it the
     * receiver rejects its Hello as unauthorised. The PC only answers while a
     * pairing window is open (the user ran Pair_Phone.bat) and closes it on the
     * first success, so this is not a token any LAN device can ask for at will.
     *
     * Returns null when no window is open, which is the ordinary case and not
     * an error worth shouting about — the caller retries after the user has run
     * the pairing tool.
     */
    fun pairOverWifi(host: String): String? {
        return try {
            val conn = URL("http://$host:$PORT/pair").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(body).optString("token").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun post(host: String, path: String): Boolean {
        return try {
            val conn = URL("http://$host:$PORT$path").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.setRequestProperty("Content-Length", "0")
            conn.doOutput = true
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }
}
