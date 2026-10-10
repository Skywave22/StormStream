package com.stormstream.app.player

import android.content.Context
import com.stormstream.app.data.Episode
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.SettingsStore
import com.stormstream.app.data.StreamSource
import com.stormstream.app.net.StormHttpClient
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** One selectable audio/subtitle track reported by libmpv. */
data class TrackInfo(
    val id: Int,
    val type: String, // "audio" | "sub" | "video"
    val title: String?,
    val lang: String?,
    val codec: String?,
    val selected: Boolean,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
) {
    val label: String get() = buildString {
        if (title != null) append(title)
        if (lang != null) {
            if (isNotEmpty()) append(" · ")
            append(lang.uppercase())
        }
        if (isEmpty()) append("Track $id")
        if (isForced) append(" (forced)")
    }
}

/**
 * The one and only player in StormStream: an **internal libmpv** instance
 * (bundled in the app via `io.github.abdallahmehiz:mpv-android-lib`).
 *
 * There is exactly one [MPV] instance per app process, created lazily and
 * reused for every item/episode/source. It owns:
 *  - playback state flows (playing, position, duration, buffering, speed, …)
 *  - track listing/selection (audio + subtitles)
 *  - per-source HTTP headers and external subtitle tracks
 *  - end-of-file events (used for "auto play next episode")
 *
 * The UI binds a [is.xyz.mpv.BaseMPVView] to [mpv]; the [PlaybackService]
 * keeps audio alive in the background with a notification.
 */
class MpvPlayerController private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SettingsStore.get(appContext)

    private var mpvInstance: MPV? = null

    /** The libmpv instance. Created on first access. */
    val mpv: MPV
        get() = mpvInstance ?: createMpv().also { mpvInstance = it }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---------- state ----------

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _position = MutableStateFlow(0.0)
    val position: StateFlow<Double> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0.0)
    val duration: StateFlow<Double> = _duration.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _speed = MutableStateFlow(1.0)
    val speed: StateFlow<Double> = _speed.asStateFlow()

    private val _tracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val tracks: StateFlow<List<TrackInfo>> = _tracks.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val audioTracks: StateFlow<List<TrackInfo>> = _audioTracks.asStateFlow()

    private val _subTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val subTracks: StateFlow<List<TrackInfo>> = _subTracks.asStateFlow()

    private val _subtitlesVisible = MutableStateFlow(true)
    val subtitlesVisible: StateFlow<Boolean> = _subtitlesVisible.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Emits when playback reaches the end of the file (reason = eof). */
    private val _playbackEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playbackEnded: SharedFlow<Unit> = _playbackEnded

    // ---------- current item ----------

    @Volatile var currentItem: MediaItem? = null; private set
    @Volatile var currentEpisode: Episode? = null; private set
    @Volatile var currentSource: StreamSource? = null; private set

    val title: String get() = buildString {
        currentItem?.let { append(it.title) }
        currentEpisode?.let { ep ->
            if (isNotEmpty()) append(" — ")
            append("S${ep.season} E${ep.number}")
            ep.title?.let { append(" · $it") }
        }
    }

    // ---------- lifecycle ----------

    private fun createMpv(): MPV {
        val instance = MPV(
            context = appContext,
            configDir = File(appContext.filesDir, "mpv").absolutePath,
            cacheDir = File(appContext.cacheDir, "mpv").absolutePath,
        )
        // Sensible defaults for a streaming app.
        runCatching { instance.setPropertyString("hwdec", "auto") }
        runCatching { instance.setPropertyString("alang", "en") }
        runCatching { instance.setPropertyString("slang", "en") }
        runCatching { instance.setPropertyString("keep-open", "yes") }
        runCatching { instance.setPropertyString("osc", "no") }
        runCatching { instance.setPropertyInt("osd-duration", 1500) }
        // Apply persisted settings.
        scope.launch {
            settings.hwdec.collectLatest { on ->
                runCatching { instance.setPropertyString("hwdec", if (on) "auto" else "no") }
            }
        }
        scope.launch {
            settings.subtitleScale.collectLatest { scale ->
                runCatching { instance.setPropertyDouble("sub-scale", scale) }
            }
        }
        // Keep the derived track lists in sync.
        scope.launch {
            _tracks.collectLatest { tracks ->
                _audioTracks.value = tracks.filter { it.type == "audio" }
                _subTracks.value = tracks.filter { it.type == "sub" }
            }
        }
        observeProperties(instance)
        instance.addObserver(MpvEventObserver(instance))
        return instance
    }

    private fun observeProperties(instance: MPV) {
        scope.launch {
            instance.propFlow<Boolean>("pause").collectLatest { paused ->
                _isPlaying.value = paused == false
            }
        }
        scope.launch {
            instance.propFlow<Double>("time-pos").collectLatest { pos ->
                if (pos != null) _position.value = pos
            }
        }
        scope.launch {
            instance.propFlow<Double>("duration").collectLatest { dur ->
                if (dur != null) _duration.value = dur
            }
        }
        scope.launch {
            instance.propFlow<Boolean>("paused-for-cache").collectLatest { pausedForCache ->
                _isBuffering.value = pausedForCache == true
            }
        }
        scope.launch {
            instance.propFlow<Double>("speed").collectLatest { spd ->
                if (spd != null) _speed.value = spd
            }
        }
        scope.launch {
            instance.propFlow<Boolean>("sub-visibility").collectLatest { visible ->
                if (visible != null) _subtitlesVisible.value = visible
            }
        }
        scope.launch {
            instance.propFlow<MPVNode>("track-list").collectLatest { node ->
                _tracks.value = node?.let(::parseTracks).orEmpty()
            }
        }
    }

    private fun parseTracks(node: MPVNode): List<TrackInfo> {
        val arr = node.asArray() ?: return emptyList()
        return arr.mapNotNull { entry ->
            val map = entry.asMap() ?: return@mapNotNull null
            val id = map["id"]?.asInt()?.toInt() ?: return@mapNotNull null
            val type = map["type"]?.asString() ?: return@mapNotNull null
            if (type != "audio" && type != "sub") return@mapNotNull null
            TrackInfo(
                id = id,
                type = type,
                title = map["title"]?.asString(),
                lang = map["lang"]?.asString(),
                codec = map["codec"]?.asString(),
                selected = map["selected"]?.asBoolean() == true,
                isDefault = map["default"]?.asBoolean() == true,
                isForced = map["forced"]?.asBoolean() == true,
            )
        }
    }

    private inner class MpvEventObserver(private val instance: MPV) : MPV.EventObserver {
        override fun eventProperty(property: String) {}
        override fun eventProperty(property: String, value: Long) {}
        override fun eventProperty(property: String, value: Boolean) {}
        override fun eventProperty(property: String, value: String) {}
        override fun eventProperty(property: String, value: Double) {}
        override fun eventProperty(property: String, value: MPVNode) {}

        override fun event(eventId: Int, data: MPVNode) {
            when (eventId) {
                MPV.mpvEvent.MPV_EVENT_START_FILE -> {
                    _isBuffering.value = true
                }
                MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> {
                    _error.value = null
                    _isBuffering.value = false
                    scope.launch {
                        // track-list changes shortly after load; re-read it.
                        delay(250)
                        _tracks.value = parseTracks(instance.getPropertyNode("track-list") ?: MPVNode.None)
                    }
                }
                MPV.mpvEvent.MPV_EVENT_END_FILE -> {
                    val reason = data["reason"]?.asString()
                    when (reason) {
                        "eof" -> {
                            _isBuffering.value = false
                            _playbackEnded.tryEmit(Unit)
                        }
                        "error" -> {
                            val err = data["file_error"]?.asString()
                                ?: data["error"]?.asString()
                                ?: "Playback error"
                            _error.value = err
                            _isBuffering.value = false
                        }
                        else -> Unit // stop / redirect / quit — nothing to do
                    }
                }
            }
        }
    }

    // ---------- playback control ----------

    /**
     * Play a stream. Applies per-source HTTP headers, attaches external
     * subtitle tracks, restores speed/subtitle settings and starts the
     * background playback service.
     */
    fun play(
        item: MediaItem,
        episode: Episode?,
        source: StreamSource,
    ) {
        currentItem = item
        currentEpisode = episode
        currentSource = source
        _error.value = null
        _position.value = 0.0
        _duration.value = 0.0

        val instance = mpv

        // Per-source HTTP headers (mpv string-list: "Name: value,Name2: value2").
        val headerFields = source.headers.entries.joinToString(",") { "${it.key}: ${it.value}" }
        runCatching {
            instance.setPropertyString(
                "http-header-fields",
                if (headerFields.isNotBlank()) headerFields
                else "User-Agent: ${StormHttpClient.USER_AGENT}"
            )
        }

        // External subtitle tracks (e.g. from Stremio addons / JS plugins).
        if (source.subtitles.isNotEmpty()) {
            runCatching { instance.command("sub-remove", "secondary") }
            source.subtitles.forEachIndexed { index, sub ->
                val flags = if (index == 0) "select" else "auto"
                runCatching {
                    instance.command("sub-add", sub.url, flags, sub.label, sub.language)
                }
            }
        }

        // Load the stream (replace current playlist entry).
        runCatching { instance.command("loadfile", source.url, "replace") }

        // Apply persisted player settings.
        scope.launch {
            val speed = settings.defaultSpeed.first()
            if (speed != 1.0) runCatching { instance.setPropertyDouble("speed", speed) }
            val subScale = settings.subtitleScale.first()
            runCatching { instance.setPropertyDouble("sub-scale", subScale) }
        }

        runCatching { instance.setPropertyBoolean("pause", false) }

        PlaybackService.start(appContext, title)
    }

    fun togglePause() {
        val instance = mpvInstance ?: return
        val paused = instance.prop<Boolean>("pause") ?: true
        runCatching { instance.setPropertyBoolean("pause", !paused) }
        PlaybackService.update(appContext, title, !paused)
    }

    fun seekTo(seconds: Double) {
        val instance = mpvInstance ?: return
        runCatching { instance.command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute") }
    }

    fun seekBy(deltaSeconds: Double) {
        val target = (_position.value + deltaSeconds).coerceAtLeast(0.0)
        seekTo(target)
    }

    fun setSpeed(speed: Double) {
        val instance = mpvInstance ?: return
        runCatching { instance.setPropertyDouble("speed", speed.coerceIn(0.25, 8.0)) }
    }

    fun selectAudioTrack(id: Int) {
        runCatching { mpv.command("set", "aid", id.toString()) }
    }

    fun selectSubTrack(id: Int?) {
        runCatching {
            if (id == null) mpv.command("set", "sid", "no")
            else mpv.command("set", "sid", id.toString())
        }
    }

    fun toggleSubtitles() {
        val instance = mpvInstance ?: return
        val visible = instance.prop<Boolean>("sub-visibility") ?: true
        runCatching { instance.setPropertyBoolean("sub-visibility", !visible) }
    }

    /** Stop playback and the background service (the libmpv instance stays alive). */
    fun stop() {
        runCatching { mpvInstance?.command("stop") }
        _isPlaying.value = false
        _position.value = 0.0
        PlaybackService.stop(appContext)
    }

    /** Release the libmpv instance entirely (app teardown / panic stop). */
    fun release() {
        PlaybackService.stop(appContext)
        runCatching { mpvInstance?.close() }
        mpvInstance = null
    }

    fun onCleared() {
        scope.cancel()
    }

    companion object {
        @Volatile
        private var instance: MpvPlayerController? = null

        fun get(context: Context): MpvPlayerController =
            instance ?: synchronized(this) {
                instance ?: MpvPlayerController(context.applicationContext).also { instance = it }
            }
    }
}
