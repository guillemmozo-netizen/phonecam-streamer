package com.phonecam.streamer.speedtest

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import kotlin.random.Random

private const val TAG = "SpeedTest"
private const val TEST_FILE_SIZE = 10 * 1024 * 1024 // 10 MB
private const val COMMAND_UPLOAD = 1
private const val COMMAND_DOWNLOAD = 2
private const val COMMAND_CLEANUP = 3

data class SpeedTestResult(
    val uploadSpeedMbps: Double,
    val downloadSpeedMbps: Double,
    val fileSizeMB: Double,
    val uploadTimeMs: Long,
    val downloadTimeMs: Long,
)

/** This class has no Android Context (it's plain java.net socket code, kept
 * that way on purpose so it doesn't need one) — reports progress as one of
 * these instead of a literal string, so the caller (which does have a
 * Context) can map each phase to a localized string. */
enum class SpeedTestPhase {
    GENERATING_FILE,
    UPLOADING,
    DOWNLOADING,
    CLEANING_UP,
}

class SpeedTestManager(
    private val host: String,
    private val port: Int,
) {

    fun runTest(cacheDir: File, onProgress: (SpeedTestPhase) -> Unit): SpeedTestResult {
        val testFile = File(cacheDir, "speedtest_${System.currentTimeMillis()}.bin")

        try {
            // 1. Generate random file
            onProgress(SpeedTestPhase.GENERATING_FILE)
            generateRandomFile(testFile)
            val fileSizeBytes = testFile.length()
            val fileSizeMB = fileSizeBytes / (1024.0 * 1024.0)

            Socket(host, port).use { socket ->
                val output = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())

                // 2. Upload: phone → PC
                onProgress(SpeedTestPhase.UPLOADING)
                val uploadStart = System.currentTimeMillis()

                output.writeInt(COMMAND_UPLOAD)
                output.writeLong(fileSizeBytes)
                output.flush()

                val fileBytes = testFile.readBytes()
                output.write(fileBytes)
                output.flush()

                val uploadAck = input.readByte()
                val uploadEnd = System.currentTimeMillis()
                val uploadTimeMs = uploadEnd - uploadStart

                Log.i(TAG, "Upload complete: ${uploadTimeMs}ms, ack=$uploadAck")

                // 3. Download: PC → phone
                onProgress(SpeedTestPhase.DOWNLOADING)
                val downloadStart = System.currentTimeMillis()

                output.writeInt(COMMAND_DOWNLOAD)
                output.flush()

                val downloadSize = input.readLong()
                val downloadBuffer = ByteArray(downloadSize.toInt())
                input.readFully(downloadBuffer)

                val downloadEnd = System.currentTimeMillis()
                val downloadTimeMs = downloadEnd - downloadStart

                Log.i(TAG, "Download complete: ${downloadTimeMs}ms, size=$downloadSize")

                // 4. Tell PC to clean up
                onProgress(SpeedTestPhase.CLEANING_UP)
                output.writeInt(COMMAND_CLEANUP)
                output.flush()

                // Sub-millisecond transfers (e.g. over loopback) would otherwise divide by
                // zero and report an "Infinity Mbps" result.
                val uploadSpeedMbps = (fileSizeBytes * 8.0) / (uploadTimeMs.coerceAtLeast(1) * 1000.0)
                val downloadSpeedMbps = (downloadSize * 8.0) / (downloadTimeMs.coerceAtLeast(1) * 1000.0)

                return SpeedTestResult(
                    uploadSpeedMbps = uploadSpeedMbps,
                    downloadSpeedMbps = downloadSpeedMbps,
                    fileSizeMB = fileSizeMB,
                    uploadTimeMs = uploadTimeMs,
                    downloadTimeMs = downloadTimeMs,
                )
            }
        } finally {
            if (testFile.exists()) {
                testFile.delete()
                Log.i(TAG, "Test file deleted")
            }
        }
    }

    private fun generateRandomFile(file: File) {
        val buffer = ByteArray(TEST_FILE_SIZE)
        Random.nextBytes(buffer)
        file.writeBytes(buffer)
    }
}
