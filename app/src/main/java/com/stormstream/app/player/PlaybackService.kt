package com.stormstream.app.player

import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.stormstream.app.MainActivity
import com.stormstream.app.data.StreamSource
import com.stormstream.app.data.StreamType

/**
 * App-wide MediaSession playback service.
 *
 * Phase 1 fixes:
 *  - Owns the MediaSession connected to an ExoPlayer for headset / lock-screen
 *    / notification controls. The screen's PlayerView connects to the same
 *    service controller for background audio and audio focus handling.
 *  - Configures audio attributes (USAGE_MEDIA, audio focus) and wake lock.
 *  - Exposes [buildMediaSource] as a shared helper that honors per-source HTTP
 *    headers and attaches subtitles via SubtitleConfiguration.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        val newPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player = newPlayer

        session = MediaSession.Builder(this, newPlayer)
            .setSessionActivity(
                TaskStackBuilder.create(this).run {
                    addNextIntent(Intent(this@PlaybackService, MainActivity::class.java))
                    getPendingIntent(
                        0,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )!!
                }
            )
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = session

    public override fun onDestroy() {
        player?.stop()
        player?.release()
        player = null
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StormPlayback"

        /** Build the correct MediaSource for a [StreamSource], with HTTP headers
         *  and subtitles attached via [ExoMediaItem.SubtitleConfiguration]. */
        fun buildMediaSource(
            factory: DefaultHttpDataSource.Factory,
            source: StreamSource,
        ): MediaSource {
            val uri = android.net.Uri.parse(source.url)

            val subtitleConfigs = source.subtitles.mapNotNull { sub ->
                val mimeType = when {
                    sub.url.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                    sub.url.endsWith(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
                    sub.url.endsWith(".ass", ignoreCase = true) -> MimeTypes.TEXT_SSA
                    else -> MimeTypes.TEXT_VTT
                }
                try {
                    ExoMediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                        .setMimeType(mimeType)
                        .setLanguage(sub.language)
                        .setLabel(sub.label)
                        .setSelectionFlags(C.SELECTION_FLAG_AUTOSELECT)
                        .build()
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping subtitle ${sub.url}", e)
                    null
                }
            }

            val mediaItemBuilder = ExoMediaItem.Builder().setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(source.name).build()
                )
            if (subtitleConfigs.isNotEmpty()) {
                mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
            }
            val mediaItem = mediaItemBuilder.build()

            val dataSourceFactory = factory
                .setDefaultRequestProperties(source.headers)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent(
                    "Mozilla/5.0 (Linux; Android 14; StormStream/0.2) AppleWebKit/537.36"
                )

            return when (source.type) {
                StreamType.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem)
                StreamType.DASH -> DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
                StreamType.MP4, StreamType.MKV, StreamType.UNKNOWN, StreamType.SUBTITLE_ONLY ->
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(mediaItem)
            }
        }
    }
}
