package com.phonecam.streamer.screen

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phonecam.streamer.MainActivity
import com.phonecam.streamer.R

/**
 * The foreground anchor a screen-capture session legally requires, and the
 * only way to stop that session from outside the app.
 *
 * Android 10+ refuses `MediaProjectionManager.getMediaProjection` (and 14+
 * refuses `createVirtualDisplay`) unless the app has a running foreground
 * service of type `mediaProjection` — the notification it posts is the
 * system's guarantee to the user that "your screen is being shared" cannot
 * happen invisibly. It also keeps the process (and with it the GL/encoder/
 * network threads that live in [com.phonecam.streamer.streaming.CameraStreamer])
 * out of the background-kill tier, which is what lets the stream survive the
 * user leaving the app.
 *
 * That last part is exactly why the notification carries a **Stop** action.
 * Casting the screen means being somewhere else — another app, the home
 * screen — and the only control the app offered was a button inside itself.
 * Reported from use as "there is no way to stop it": true, and worse if the
 * activity had been destroyed in the background, because coming back showed
 * an idle-looking app while the system was still sharing the screen.
 */
class ScreenCaptureService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The activity tears the session down properly (virtual display,
            // projection, encoder, UI) when it is alive to do it. When it is
            // not, stopping this service is what revokes the projection, so
            // the screen stops being shared either way.
            val listener = onStopRequested
            if (listener != null) {
                listener()
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.screen_capture_notification_title),
            // Low importance: the projection consent dialog already told the
            // user; this is standing state, not news that should chime.
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val tapBack = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_source_screen)
            .setContentTitle(getString(R.string.screen_capture_notification_title))
            .setContentText(getString(R.string.screen_capture_notification_text))
            .setContentIntent(tapBack)
            .addAction(0, getString(R.string.screen_capture_stop), stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Now — and only now — may the caller create the MediaProjection:
        // the type check above is what the projection APIs verify.
        onReady?.invoke()
        onReady = null
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 0x5C
        private const val ACTION_STOP = "com.phonecam.streamer.screen.STOP"

        /**
         * Invoked exactly once, from onStartCommand, after startForeground has
         * completed — i.e. the first moment a MediaProjection may be created.
         * Process-local by nature (the service runs in the app's own process).
         */
        @Volatile
        var onReady: (() -> Unit)? = null

        /**
         * How the notification's Stop action reaches the session's owner.
         * Set while a screen session runs and cleared when it ends — a stale
         * one would hold a destroyed activity and act on a dead session.
         */
        @Volatile
        var onStopRequested: (() -> Unit)? = null

        /** True when a session's foreground service is up — see [isRunning]. */
        @Volatile
        private var running = false

        val isRunning: Boolean get() = running

        fun start(context: Context) {
            running = true
            ContextCompat.startForegroundService(
                context, Intent(context, ScreenCaptureService::class.java),
            )
        }

        fun stop(context: Context) {
            running = false
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}
