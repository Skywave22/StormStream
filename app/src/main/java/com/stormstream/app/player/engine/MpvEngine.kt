package com.stormstream.app.player.engine

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.stormstream.app.data.StreamSource
import java.lang.ref.WeakReference

/**
 * libmpv based player engine.
 *
 * This engine talks to libmpv via JNI. If libmpv.so is not present on the
 * device/bundled in the APK, [MpvEngine] throws on init and the factory
 * falls back to [ExoPlayerEngine] automatically (see [PlayerEngine.create]).
 *
 * To bundle libmpv, drop the armeabi-v7a/arm64-v8a/x86_64 `libmpv.so` files
 * into app/src/main/jniLibs/<abi>/ and this engine will pick them up.
 * The API mirrors what mpv-android exposes at the native layer.
 */
class MpvEngine : PlayerEngine {

    private var nativeHandle: Long = 0
    private var attachedView: TextureView? = null
    private var surface: Surface? = null
    private var listener: PlayerEngine.Listener? = null
    private var ready = false
    private var lastPosition: Long = 0
    private var lastDuration: Long = 0

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            val s = Surface(st)
            surface = s
            setSurfaceNative(nativeHandle, s)
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            // mpv handles resize internally via vo.
        }
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            setSurfaceNative(nativeHandle, null)
            surface?.release()
            surface = null
            return true
        }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    init {
        if (!tryLoad()) throw RuntimeException("libmpv not available on device")
        nativeHandle = createNative()
        if (nativeHandle == 0L) throw RuntimeException("mpv native init failed")
        setCallbackNative(nativeHandle, WeakReference(this))
    }

    override fun attach(view: View) {
        require(view is TextureView) { "MpvEngine requires a TextureView" }
        detached()
        attachedView = view
        if (view.isAvailable) {
            val s = Surface(view.surfaceTexture)
            surface = s
            setSurfaceNative(nativeHandle, s)
        }
        view.surfaceTextureListener = surfaceListener
    }

    private fun detached() {
        attachedView?.surfaceTextureListener = null
        surface?.release()
        surface = null
        attachedView = null
    }

    override fun detach() = detached()

    override fun play() {
        commandNative(nativeHandle, "set_property", "pause", "no")
    }
    override fun pause() {
        commandNative(nativeHandle, "set_property", "pause", "yes")
    }
    override fun stop() {
        commandNative(nativeHandle, "stop")
        ready = false
    }
    override fun release() {
        detach()
        if (nativeHandle != 0L) {
            releaseNative(nativeHandle)
            nativeHandle = 0L
        }
    }
    override fun seekTo(positionMs: Long) {
        commandNative(nativeHandle, "seek", "${positionMs / 1000.0}", "absolute")
    }
    override fun setVolume(v: Float) {
        commandNative(nativeHandle, "set_property", "volume", (v * 100f).toInt().toString())
    }
    override var playWhenReady: Boolean = true
        set(value) {
            field = value
            if (value) play() else pause()
        }
    override val currentPosition: Long get() = lastPosition
    override val duration: Long get() = lastDuration
    override var isPlaying: Boolean = false
        private set
    override val isBuffering: Boolean get() = !ready
    override var error: String? = null
        private set

    override fun load(source: StreamSource) {
        ready = false
        error = null
        // mpv handles http headers via the http-header-fields option or via
        // the stream_open callback. For simplicity, set per-file headers using
        // the "file-local-options/" prefix.
        val optHeader = source.headers.entries.joinToString(",") { "${it.key}: ${it.value}" }
        val url = if (optHeader.isNotBlank())
            "$source.url|http-header-fields=$optHeader"
        else source.url
        commandNative(nativeHandle, "loadfile", url)
    }
    override fun setListener(listener: PlayerEngine.Listener?) { this.listener = listener }

    // --- native callbacks (called from JNI via reflection) ---

    private fun onEndFile(reason: Int) {
        when (reason) {
            REASON_EOF -> { listener?.onCompleted(); isPlaying = false }
            REASON_ERROR -> { error = "Playback error"; listener?.onError("mpv error"); isPlaying = false }
        }
    }

    private fun onPlaybackRestart() {
        ready = true
        listener?.onPlaying()
        isPlaying = true
    }

    private fun onDuration(seconds: Double) {
        lastDuration = (seconds * 1000).toLong()
        listener?.onDurationChanged(lastDuration)
    }

    private fun onTime(seconds: Double) {
        lastPosition = (seconds * 1000).toLong()
        listener?.onPositionUpdate(lastPosition)
    }

    private fun onIdle() { ready = false }

    // --- JNI ---

    @Synchronized
    private external fun createNative(): Long
    @Synchronized
    private external fun releaseNative(handle: Long)
    @Synchronized
    private external fun setSurfaceNative(handle: Long, surface: Surface?)
    @Synchronized
    private external fun commandNative(handle: Long, vararg args: String): Int
    @Synchronized
    private external fun setCallbackNative(handle: Long, self: WeakReference<MpvEngine>)

    companion object {
        private const val REASON_EOF = 0
        private const val REASON_ERROR = 3
        private var loaded = false
        private var loadAttempted = false

        @Synchronized
        fun tryLoad(): Boolean {
            if (loadAttempted) return loaded
            loadAttempted = true
            // Try to load a libmpv shared library (either shipped as jniLibs/libmpv.so
            // or from mpv-android bundle). If neither exists, this returns false and
            // the factory falls back to ExoPlayer.
            loaded = runCatching {
                System.loadLibrary("stormmpv"); true
            }.recoverCatching {
                System.loadLibrary("mpv"); true
            }.getOrDefault(false)
            return loaded
        }

        init { /* do NOT try to load at class init — defer to tryLoad() */ }
    }
}
