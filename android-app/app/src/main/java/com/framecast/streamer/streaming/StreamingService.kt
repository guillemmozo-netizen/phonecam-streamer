package com.framecast.streamer.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.framecast.streamer.MainActivity
import com.framecast.streamer.R

private const val TAG = "StreamingService"
private const val CHANNEL_ID = "framecast_streaming"
private const val NOTIFICATION_ID = 1

/**
 * Keeps the camera and microphone alive while FrameCast is not on screen.
 *
 * Without this the app is a webcam that stops working the moment the user
 * opens the app they wanted to appear in: Android suspends camera and
 * microphone access for processes that are not in the foreground, so pressing
 * Home, locking the screen or switching to Zoom ended the stream. The OEM
 * power managers on Samsung, Xiaomi, Oppo, Vivo and Huawei are also
 * substantially less willing to kill a process with a foreground service.
 *
 * It deliberately does **not** own the capture pipeline. CameraX is bound to
 * MainActivity's lifecycle and moving it here would be the kind of rewrite this
 * change is not — the service's job is to hold the process in a state where
 * that pipeline is allowed to keep running, and to give the user a visible,
 * dismissible way to stop it.
 */
class StreamingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val types = intent?.getStringArrayListExtra(EXTRA_TYPES)
            ?.mapNotNull { runCatching { CaptureKind.valueOf(it) }.getOrNull() }
            ?.toSet()
            .orEmpty()

        if (!ForegroundTypePolicy.canStart(types)) {
            // Nothing to hold in the foreground, and calling startForeground
            // with no type for a capture use is itself a violation on 14+.
            Log.w(TAG, "no granted capture types; not starting")
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), androidTypeMask(types))
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            // SecurityException when a declared type lacks its permission,
            // ForegroundServiceStartNotAllowedException when started from the
            // background on 12+. Neither should take the app down - streaming
            // still works while the app is on screen.
            Log.e(TAG, "could not enter the foreground; streaming will stop when backgrounded", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // START_NOT_STICKY: if the system kills us, silently resurrecting a
        // camera and microphone capture with no user present is the wrong
        // behaviour. The user restarts the stream.
        return START_NOT_STICKY
    }

    private fun androidTypeMask(types: Set<CaptureKind>): Int {
        var mask = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (CaptureKind.CAMERA in types) mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (CaptureKind.MICROPHONE in types) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        return mask
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // IMPORTANCE_LOW: the notification has to exist, but a webcam that
        // beeps every time you start it would be worse than no notification.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.streaming_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, StreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.streaming_notification_title))
            .setContentText(getString(R.string.streaming_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.stop_stream_button), stop
                ).build()
            )
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.framecast.streamer.STOP"
        private const val EXTRA_TYPES = "types"

        /**
         * Starts the service for the capture types actually available.
         *
         * Returns false when nothing is grantable, so the caller knows the
         * stream will not survive backgrounding rather than assuming it will.
         */
        fun start(context: Context, types: Set<CaptureKind>): Boolean {
            if (!ForegroundTypePolicy.canStart(types)) return false
            val intent = Intent(context, StreamingService::class.java).putStringArrayListExtra(
                EXTRA_TYPES, ArrayList(types.map { it.name })
            )
            return runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "could not start the streaming service", it) }
                .isSuccess
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, StreamingService::class.java)) }
        }
    }
}
