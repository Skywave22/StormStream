package com.stormstream.app.player

import android.content.Context
import android.util.AttributeSet
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV

/**
 * StormStream's libmpv video surface — the ONE player view in the app.
 *
 * A thin subclass of mpv-android's [BaseMPVView] (bundled internally via
 * `io.github.abdallahmehiz:mpv-android-lib`). The base class owns the single
 * [MPV] instance (`view.mpv`), initializes libmpv through
 * [BaseMPVView.initialize] and attaches/detaches the rendering surface
 * automatically. This subclass only supplies StormStream's startup options
 * and the set of properties observed at runtime.
 */
class StormMpvView(context: Context, attrs: AttributeSet? = null) : BaseMPVView(context, attrs) {

    override fun initOptions() {
        // Called before mpv.init() — these are startup options.
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("hwdec", "auto")
        mpv.setOptionString("alang", "en")
        mpv.setOptionString("slang", "en")
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("osc", "no")
        mpv.setOptionString("osd-duration", "1500")
        mpv.setOptionString("tls-verify", "yes")
    }

    override fun postInitOptions() {
        // Called right after mpv.init() — nothing extra needed.
    }

    override fun observeProperties() {
        // Properties mirrored into the controller's StateFlows at runtime.
        mpv.observeProperty("pause", MPV.mpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("time-pos", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("paused-for-cache", MPV.mpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("speed", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("sub-visibility", MPV.mpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("track-list", MPV.mpvFormat.MPV_FORMAT_NODE)
    }
}
