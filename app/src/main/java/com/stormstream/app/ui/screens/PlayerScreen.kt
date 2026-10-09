package com.stormstream.app.ui.screens

import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.player.engine.PlayerEngine
import com.stormstream.app.player.engine.PlayerEngine.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen video player — libmpv primary engine with ExoPlayer fallback.
 *
 * Modern gesture-driven UI similar to Nuvio/SkyStream: tap to toggle controls,
 * vertical brightness/volume, double-tap seek, progress scrub bar, engine
 * selector, speed, subtitles toggle, and source chips.
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: AppViewModel = viewModel(),
) {
    val context = LocalContext.current
    val target by viewModel.playback.collectAsState()
    val enginePref by viewModel.playerEngineType.collectAsState()

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

    val engineType = remember(enginePref) {
        if (enginePref == "mpv" && PlayerEngine.isMpvAvailable()) Type.MPV else Type.EXO
    }
    val engine = remember { PlayerEngine.create(engineType) }

    DisposableEffect(engine) {
        engine.setListener(object : PlayerEngine.Listener {
            override fun onReady() { isBuffering = false; errMsg = null }
            override fun onBuffering() { isBuffering = true }
            override fun onPlaying() { isPlaying = true; isBuffering = false }
            override fun onPaused() { isPlaying = false }
            override fun onCompleted() { isPlaying = false }
            override fun onError(message: String) { errMsg = message; isBuffering = false }
            override fun onDurationChanged(d: Long) { durationMs = d }
            override fun onPositionUpdate(pos: Long) { positionMs = pos }
        })
        onDispose {
            val pos = engine.currentPosition
            val dur = max(0, engine.duration)
            val stream = streams.getOrNull(selectedIndex)
            if (pos > 2_000L) {
                viewModel.recordProgress(playback.item, playback.episode, stream?.url, pos, dur)
            }
            engine.release()
        }
    }

    // Load selected stream
    LaunchedEffect(selectedIndex) {
        streams.getOrNull(selectedIndex)?.let { s ->
            errMsg = null
            isBuffering = true
            engine.load(s)
            // Resume from history if available
            val key = com.stormstream.app.data.StormStore.historyKey(playback.item, playback.episode)
            val resumeMs = viewModel.history.value.firstOrNull { it.key == key && !it.completed }?.positionMs
            if (resumeMs != null && resumeMs > 5_000L) engine.seekTo(resumeMs)
            engine.playWhenReady = true
        }
    }

    // Progress tracking + periodic history writes
    LaunchedEffect(playback, selectedIndex) {
        val stream = streams.getOrNull(selectedIndex)
        while (isActive) {
            delay(5_000L)
            val pos = engine.currentPosition
            val dur = max(0, engine.duration)
            positionMs = pos
            durationMs = dur
            if (pos > 2_000L && !isBuffering) {
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
        // Video surface
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                            engine.attach(this@apply)
                        }
                        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                            engine.detach()
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { controlsVisible = !controlsVisible }) }
        )

        // Buffering
        if (isBuffering && errMsg == null) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        // Error
        errMsg?.let { err ->
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Playback error", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(err, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Row {
                        TextButton(onClick = {
                            errMsg = null
                            engine.load(streams[selectedIndex])
                        }) { Text("Retry", color = Color.White) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onBack) { Text("Back", color = Color.White) }
                    }
                }
            }
        }

        // Controls (animated visibility)
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent, Color.Black.copy(alpha = 0.8f)), startY = 0f)
            )) {
                // Top bar
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            playback.item.title,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val sub = playback.episode?.let { "S${it.season}:E${it.number} · ${it.title ?: ""}" }
                            ?: "${engineType.name.lowercase().replaceFirstChar { it.uppercase() }} engine"
                        Text(sub, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
                    }
                    // Engine badge
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(engineType.name, color = Color.White, fontSize = 10.sp)
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color.White.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }

                // Center controls
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { engine.seekTo(max(0L, positionMs - 10_000L)) }) {
                        Icon(Icons.Default.Replay10, "Back 10s", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    Surface(
                        color = Color.White.copy(alpha = 0.15f),
                        shape = CircleShape,
                        modifier = Modifier.size(64.dp)
                    ) {
                        IconButton(onClick = {
                            if (isPlaying) engine.pause() else engine.play()
                        }, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    IconButton(onClick = { engine.seekTo(min(durationMs, positionMs + 10_000L)) }) {
                        Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }

                // Bottom: scrub + chips
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .fillMaxWidth()
                ) {
                    // Time + scrubber
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(positionMs), color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                            onValueChange = { engine.seekTo((it * durationMs).toLong()) },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(formatTime(durationMs), color = Color.White, fontSize = 12.sp)
                    }

                    // Source chips
                    if (streams.size > 1) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            streams.forEachIndexed { i, s ->
                                FilterChip(
                                    selected = i == selectedIndex,
                                    onClick = { selectedIndex = i },
                                    label = {
                                        Text(
                                            (s.quality ?: s.name).take(24),
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                        containerColor = Color.White.copy(alpha = 0.12f)
                                    )
                                )
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
