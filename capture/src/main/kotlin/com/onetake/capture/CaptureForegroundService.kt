package com.onetake.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the capture process visible while CameraX and AudioRecord are active.
 * The session itself remains owned by the app process; this service only owns the Android
 * foreground lifetime and notification, so a service restart cannot create a second recorder.
 */
class CaptureForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                startForeground(NOTIFICATION_ID, notification(sessionId))
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "One-Take recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Visible while One-Take records camera and microphone input"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun notification(sessionId: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("One-Take recording")
            .setContentText(if (sessionId.isBlank()) "Camera and microphone are active" else "Recording in progress")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val ACTION_START = "com.onetake.capture.action.START"
        private const val ACTION_STOP = "com.onetake.capture.action.STOP"
        private const val EXTRA_SESSION_ID = "com.onetake.capture.extra.SESSION_ID"
        private const val CHANNEL_ID = "onetake_capture"
        private const val NOTIFICATION_ID = 0x4f54

        fun start(context: Context, sessionId: String) {
            val intent = Intent(context, CaptureForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureForegroundService::class.java))
        }
    }
}
