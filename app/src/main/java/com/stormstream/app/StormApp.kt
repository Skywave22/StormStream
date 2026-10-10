package com.stormstream.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

/**
 * Application entry point: creates the playback notification channel and
 * installs a crash logger so bug reports carry the real error text.
 */
class StormApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("StormApp", "Uncaught on ${t.name}", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    "storm_playback",
                    getString(R.string.notification_channel_playback),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_playback_description)
                    setShowBadge(false)
                }
            )
        }
    }
}
