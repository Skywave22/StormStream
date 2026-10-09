package com.stormstream.app.player

import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    // A lightweight session service so audio focus, notification, and background
    // playback work. The session itself is created lazily by the player screen
    // and lives as long as the service does.
    private var session: androidx.media3.session.MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(this).build()
        session = androidx.media3.session.MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(
        controllerInfo: androidx.media3.session.MediaSession.ControllerInfo
    ): androidx.media3.session.MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
