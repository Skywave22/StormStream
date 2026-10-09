package com.stormstream.app.player.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.stormstream.app.data.StreamSource
import com.stormstream.app.data.StreamType
import java.util.concurrent.TimeUnit

/**
 * ExoPlayer-based engine (default fallback when libmpv is not bundled).
 */
class ExoPlayerEngine : PlayerEngine {

    private var appContext: Context? = null
    private var player: ExoPlayer? = null
    private var attachedView: TextureView? = null
    private var listener: PlayerEngine.Listener? = null
    private var lastSource: StreamSource? = null
    private var lastError: String? = null
    private var poller: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun ensurePlayer(ctx: Context) {
        if (player != null) return
        appContext = ctx.applicationContext
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; StormStream/0.3) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val p = ExoPlayer.Builder(ctx.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        listener?.onReady()
                        listener?.onDurationChanged(p.duration.coerceAtLeast(0L))
                    }
                    Player.STATE_BUFFERING -> listener?.onBuffering()
                    Player.STATE_ENDED -> { listener?.onCompleted(); _isPlaying = false }
                    Player.STATE_IDLE -> {}
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying = playing
                if (playing) listener?.onPlaying() else listener?.onPaused()
            }
            override fun onPlayerError(err: PlaybackException) {
                lastError = err.message ?: "Playback error"
                listener?.onError(lastError!!)
            }
        })
        player = p
        startPolling()
    }

    private fun startPolling() {
        val r = object : Runnable {
            override fun run() {
                player?.let {
                    if (it.playbackState == Player.STATE_READY) {
                        listener?.onPositionUpdate(it.currentPosition.coerceAtLeast(0L))
                    }
                }
                handler.postDelayed(this, 500L)
            }
        }
        poller = r
        handler.post(r)
    }

    override fun attach(view: View) {
        val ctx = view.context
        ensurePlayer(ctx)
        attachedView = view as? TextureView
        attachedView?.surfaceTexture?.let { player?.setVideoSurface(Surface(it)) }
    }

    override fun detach() {
        player?.clearVideoSurface()
        attachedView = null
    }

    override fun play() { player?.play() }
    override fun pause() { player?.pause() }
    override fun stop() { player?.stop() }
    override fun release() {
        poller?.let { handler.removeCallbacks(it) }
        poller = null
        player?.release()
        player = null
    }
    override fun seekTo(positionMs: Long) { player?.seekTo(positionMs) }
    override fun setVolume(v: Float) { player?.volume = v }
    override var playWhenReady: Boolean = true
        set(value) {
            field = value
            player?.playWhenReady = value
        }
    override val currentPosition: Long get() = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
    override val duration: Long get() = player?.duration?.coerceAtLeast(0L) ?: 0L
    private var _isPlaying = false
    override val isPlaying: Boolean get() = _isPlaying
    override val isBuffering: Boolean get() = player?.playbackState == Player.STATE_BUFFERING
    override val error: String? get() = lastError

    override fun load(source: StreamSource) {
        lastError = null
        lastSource = source
        val ctx = appContext ?: attachedView?.context?.applicationContext ?: return
        ensurePlayer(ctx)
        val p = player ?: return

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; StormStream/0.3) AppleWebKit/537.36")
            .setDefaultRequestProperties(source.headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val uri = Uri.parse(source.url)
        val mediaItem = ExoMediaItem.Builder().setUri(uri).build()
        val mediaSource: MediaSource = when (source.type) {
            StreamType.HLS -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            StreamType.DASH -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            else -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }
        p.setMediaSource(mediaSource)
        p.prepare()
        p.playWhenReady = playWhenReady
    }

    override fun setListener(listener: PlayerEngine.Listener?) { this.listener = listener }
}
