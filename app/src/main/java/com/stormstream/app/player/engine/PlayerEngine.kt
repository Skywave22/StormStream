package com.stormstream.app.player.engine

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import com.stormstream.app.data.StreamSource

/**
 * Abstract media player engine. We implement two concrete engines:
 *  - [ExoPlayerEngine] — reliable Media3 ExoPlayer with full HLS/DASH/MP4 support.
 *  - [MpvEngine] — libmpv based engine (loaded via JNI); falls back to ExoPlayer
 *    automatically if libmpv.so is not bundled (e.g. in CI-built APKs).
 *
 * The UI talks only to this interface; the concrete implementation is chosen
 * in Settings and bound once per PlayerScreen.
 */
interface PlayerEngine {

    /** Attach the engine to a [TextureView]/[SurfaceView] for rendering. */
    fun attach(view: View)
    fun detach()

    fun play()
    fun pause()
    fun stop()
    fun release()

    fun seekTo(positionMs: Long)
    fun setVolume(v: Float)
    var playWhenReady: Boolean

    val currentPosition: Long
    val duration: Long
    val isPlaying: Boolean
    val isBuffering: Boolean
    val error: String?

    /** Load a [StreamSource] with headers. Replaces the previous source. */
    fun load(source: StreamSource)

    /** Listener for engine events (buffering, ready, error). */
    fun setListener(listener: Listener?)

    interface Listener {
        fun onReady()
        fun onBuffering()
        fun onPlaying()
        fun onPaused()
        fun onCompleted()
        fun onError(message: String)
        fun onDurationChanged(durationMs: Long)
        fun onPositionUpdate(positionMs: Long)
    }

    enum class Type { MPV, EXO }

    companion object {
        fun create(type: Type): PlayerEngine = when (type) {
            Type.MPV -> {
                val mpv = runCatching { MpvEngine() }
                if (mpv.isSuccess) mpv.getOrThrow() else ExoPlayerEngine()
            }
            Type.EXO -> ExoPlayerEngine()
        }

        fun isMpvAvailable(): Boolean = MpvEngine.tryLoad()
    }
}
