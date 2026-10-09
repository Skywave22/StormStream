package com.stormstream.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.StormStore
import io.github.yuroyami.libmpvkt.MPV
import io.github.yuroyami.libmpvkt.compose.MpvPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen video player — **libmpv only** (via libmpvkt-compose).
 *
 * Uses [MpvPlayer] which embeds a real mpv View backed by libmpv + FFmpeg +
 * libass + dav1d (prebuilt for all four ABIs by libmpvKt). Gesture-driven UI:
 * tap to toggle controls, double-tap seeks, play/pause, scrubber, source
 * chips, speed, subtitle/audio cycle.
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: AppViewModel = viewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val target by viewModel.playback.collectAsState()
    val history by viewModel.history.collectAsState()

    if (target == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing to play", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onBack) { Text("Go back", color = Color.White) }
            }
        }
        return
    }

    val playback = target!!
    val streams = playback.streams
    var selectedIndex by remember(playback.selectedIndex) { mutableIntStateOf(playback.selectedIndex) }
    var controlsVisible by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    var mpv by remember { mutableStateOf<MPV?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            val pos = positionMs
            val dur = max(0, durationMs)
            val stream = streams.getOrNull(selectedIndex)
            if (pos > 2_000L) {
                viewModel.recordProgress(playback.item, playback.episode, stream?.url, pos, dur)
            }
            mpv?.close()
            mpv = null
        }
    }

    // Observe mpv state once we have an mpv instance.
    LaunchedEffect(mpv) {
        val m = mpv ?: return@LaunchedEffect
        launch {
            while (isActive) {
                delay(500L)
                runCatching {
                    val m = mpv ?: return@runCatching
                    val t = m.getPropertyString("time-pos")?.toDoubleOrNull() ?: 0.0
                    val d = m.getPropertyString("duration")?.toDoubleOrNull() ?: 0.0
                    val p = m.getPropertyString("pause") == "yes"
                    val coreIdle = m.getPropertyString("core-idle") == "yes"
                    val cacheBuff = m.getPropertyString("paused-for-cache") == "yes"
                    positionMs = (t * 1000).toLong()
                    durationMs = (d * 1000).toLong()
                    isPlaying = !p && !coreIdle
                    isBuffering = cacheBuff || (positionMs == 0L && d > 0 && !isPlaying)
                }
            }
        }

    }

    // Load selected stream
    LaunchedEffect(selectedIndex, mpv) {
        val s = streams.getOrNull(selectedIndex) ?: return@LaunchedEffect
        val m = mpv ?: return@LaunchedEffect
        errMsg = null
        isBuffering = true
        // Set custom http headers by writing them as a "\n"-separated string
        val headerStr = s.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        if (headerStr.isNotBlank()) {
            runCatching { m.setPropertyString("http-header-fields", headerStr) }
        }
        runCatching {
            m.setPropertyDouble("speed", speed.toDouble())
            m.command("loadfile", s.url)
        }.onFailure { errMsg = it.message }
        // Resume from history
        val key = StormStore.historyKey(playback.item, playback.episode)
        val resumeMs = history.firstOrNull { it.key == key && !it.completed }?.positionMs
        if (resumeMs != null && resumeMs > 5_000L) {
            kotlinx.coroutines.delay(800)
            runCatching { m.command("seek", "${resumeMs / 1000.0}", "absolute") }
        }
        isBuffering = false
    }

    // Periodic history writes
    LaunchedEffect(playback, selectedIndex, mpv) {
        val stream = streams.getOrNull(selectedIndex)
        while (isActive) {
            delay(5_000L)
            val pos = positionMs
            val dur = max(0, durationMs)
            if (pos > 2_000L && mpv != null) {
                viewModel.recordProgress(playback.item, playback.episode, stream?.url, pos, dur)
            }
        }
    }

    // Auto-hide controls
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3_500L)
            controlsVisible = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Video surface — MpvPlayer is the libmpvkt-compose drop-in.
        MpvPlayer(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { controlsVisible = !controlsVisible }) },
            factory = { ctx ->
                MPV(ctx) { opts ->
                    opts.setOptionString("hwdec", "auto")
                    opts.setOptionString("cache", "yes")
                    opts.setOptionString("cache-secs", "10")
                    opts.setOptionString("demuxer-max-bytes", "${300 * 1024 * 1024}")
                    opts.setOptionString("ytdl", "no")
                    opts.setOptionString("tls-verify", "no")
                    opts.setOptionString("video-scale", "lanczos")
                }.also { mpv = it }
            }
        ) { view ->
            // Called once MpvView is ready; load the stream.
            val m = view.mpv
            if (m != null && streams.getOrNull(selectedIndex) != null && mpv == null) {
                mpv = m
            }
        }

        // Buffering
        if (isBuffering && errMsg == null) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        // Error
        errMsg?.let { err ->
            Surface(color = Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Playback error", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(err, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Row {
                        TextButton(onClick = {
                            errMsg = null
                            streams.getOrNull(selectedIndex)?.let { s ->
                                mpv?.command("loadfile", s.url)
                            }
                        }) { Text("Retry", color = Color.White) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onBack) { Text("Back", color = Color.White) }
                    }
                }
            }
        }

        // Controls
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent, Color.Black.copy(alpha = 0.8f)))
            )) {
                // Top bar
                Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(playback.item.title, color = Color.White, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = playback.episode?.let { "S${it.season}:E${it.number} · ${it.title ?: ""}" } ?: "libmpv"
                        Text(sub, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
                    }
                    SuggestionChip(onClick = {},
                        label = { Text("libmpv", color = Color.White, fontSize = 10.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color.White.copy(alpha = 0.15f)))
                }

                // Center controls
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { mpv?.command("seek", "-10") }) {
                        Icon(Icons.Default.Replay10, "Back 10s", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    Surface(color = Color.White.copy(alpha = 0.15f), shape = CircleShape, modifier = Modifier.size(64.dp)) {
                        IconButton(onClick = {
                            val m = mpv ?: return@IconButton
                            val p: Boolean? = m.prop["pause"]
                            if (p == true) m.command("set", "pause", "no") else m.command("set", "pause", "yes")
                        }, modifier = Modifier.fillMaxSize()) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause", tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    }
                    IconButton(onClick = { mpv?.command("seek", "10") }) {
                        Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }

                // Bottom
                Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(positionMs), color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                            onValueChange = { mpv?.command("seek", "${(it * durationMs / 1000)}", "absolute") },
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(formatTime(durationMs), color = Color.White, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            speed = when {
                                speed >= 2f -> 0.5f
                                else -> (speed + 0.25f)
                            }
                            mpv?.setPropertyDouble("speed", speed.toDouble())
                        }) {
                            Text("${speed}x", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                        IconButton(onClick = { mpv?.command("cycle", "sub") }) {
                            Icon(Icons.Default.ClosedCaption, "Subtitles", tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = { mpv?.command("cycle", "audio") }) {
                            Icon(Icons.Default.Audiotrack, "Audio", tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                        if (streams.size > 1) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                streams.forEachIndexed { i, s ->
                                    FilterChip(
                                        selected = i == selectedIndex,
                                        onClick = { selectedIndex = i },
                                        label = { Text((s.quality ?: s.name).take(20), color = Color.White, style = MaterialTheme.typography.labelSmall) },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), containerColor = Color.White.copy(alpha = 0.12f))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
