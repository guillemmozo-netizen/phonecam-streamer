package com.phonecam.streamer.network

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
