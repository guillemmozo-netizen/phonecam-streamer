package com.framecast.streamer.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

private const val TAG = "PcDiscovery"
private const val DISCOVERY_PORT = 8789
private const val DISCOVERY_MSG = "FRAMECAST_DISCOVER"
private const val DISCOVERY_REPLY = "FRAMECAST_HERE"

object PcDiscovery {

    fun findPc(timeoutMs: Int = 3000): String? {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMs

                val msg = DISCOVERY_MSG.toByteArray()
                val broadcast = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(msg, msg.size, broadcast, DISCOVERY_PORT)
                socket.send(packet)

                val buffer = ByteArray(256)
                val response = DatagramPacket(buffer, buffer.size)

                return try {
                    socket.receive(response)
                    val reply = String(response.data, 0, response.length).trim()
                    if (reply.startsWith(DISCOVERY_REPLY)) {
                        val ip = response.address.hostAddress
                        Log.i(TAG, "Found PC at $ip")
                        ip
                    } else {
                        null
                    }
                } catch (_: SocketTimeoutException) {
                    Log.w(TAG, "No PC found via broadcast")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed", e)
            return null
        }
    }
}
