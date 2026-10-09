package com.stormstream.app

import android.app.Application
import android.util.Log

/**
 * Application entry point. Any one-time initialization (Koin/Hilt, logging,
 * WorkManager, DataStore) goes here — kept minimal for the seed build.
 */
class StormApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("StormApp", "Uncaught on ${t.name}", e)
        }
    }
}
