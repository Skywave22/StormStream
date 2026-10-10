package com.stormstream.app.player

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.stormstream.app.MainActivity
import com.stormstream.app.R

/**
 * Keeps the internal libmpv player alive in the background.
 *
 * This is a plain foreground service (mediaPlayback type) with a notification
 * carrying play/pause and stop actions. libmpv itself is owned by
 * [MpvPlayerController]; this service only keeps the process alive, holds a
 * wake lock while playing, and mirrors playback state in the notification.
 */
class PlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var title: String = "StormStream"
    private var playing: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                title = intent.getStringExtra(EXTRA_TITLE) ?: title
                playing = true
                startForegroundWithNotification()
                acquireWakeLock()
            }
            ACTION_UPDATE -> {
                intent.getStringExtra(EXTRA_TITLE)?.let { title = it }
                playing = intent.getBooleanExtra(EXTRA_PLAYING, playing)
                startForegroundWithNotification()
                if (playing) acquireWakeLock() else releaseWakeLock()
            }
            ACTION_TOGGLE -> {
                MpvPlayerController.get(applicationContext).togglePause()
            }
            ACTION_STOP -> {
                MpvPlayerController.get(applicationContext).stop()
                stopSelf()
            }
            else -> {
                // Service restarted by the system with no intent: re-show notification.
                startForegroundWithNotification()
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(title)
            .setContentText(if (playing) "Playing" else "Paused")
            .setContentIntent(openIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (playing) R.drawable.ic_stat_pause else R.drawable.ic_stat_play,
                if (playing) "Pause" else "Play",
                toggleIntent
            )
            .addAction(R.drawable.ic_stat_stop, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_playback),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_playback_description)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StormStream:playback").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        MpvPlayerController.get(applicationContext).stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val CHANNEL_ID = "storm_playback"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_START = "com.stormstream.app.action.START"
        private const val ACTION_UPDATE = "com.stormstream.app.action.UPDATE"
        private const val ACTION_TOGGLE = "com.stormstream.app.action.TOGGLE"
        private const val ACTION_STOP = "com.stormstream.app.action.STOP"

        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_PLAYING = "extra_playing"

        /** Ensure the service is running and showing the current title. */
        fun start(context: Context, title: String) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Update the notification (title / playing state). */
        fun update(context: Context, title: String, playing: Boolean) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PLAYING, playing)
            }
            context.startService(intent)
        }

        /** Stop the foreground service. */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, PlaybackService::class.java))
            }
        }
    }
}
