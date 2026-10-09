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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.StormStore
import dev.marcelsoftware.mpvcompose.MPVLib
import dev.marcelsoftware.mpvcompose.MPVPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max

/**
 * Full-screen video player — **libmpv only** via mpv-compose (MPVPlayer).
 * No ExoPlayer, no fallbacks.  FFmpeg + libass + hw decoding handled natively.
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: AppViewModel = viewModel(),
) {
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
    var posSec by remember { mutableLongStateOf(0L) }
    var durSec by remember { mutableLongStateOf(0L) }
    var isPaused by remember { mutableStateOf(false) }
    var isEof by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var errMsg by remember { mutableStateOf<String?>(null) }

    // Periodic progress save + hide controls
    LaunchedEffect(playback, selectedIndex) {
        while (isActive) {
            delay(5_000L)
            if (durSec > 0 && posSec > 2) {
                val stream = streams.getOrNull(selectedIndex)
                viewModel.recordProgress(
                    playback.item, playback.episode, stream?.url,
                    posSec * 1000L, durSec * 1000L
                )
            }
        }
    }

    LaunchedEffect(controlsVisible, !isPaused) {
        if (controlsVisible && !isPaused) {
            delay(3_500L)
            controlsVisible = false
        }
    }

    // Load the selected stream (with headers + resume).
    fun loadCurrent() {
        val s = streams.getOrNull(selectedIndex) ?: return
        errMsg = null
        isEof = false
        // mpv accepts custom http headers via the http-header-fields string property,
        // using "\n"-separated "Key: Value" lines.
        val headerStr = s.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        if (headerStr.isNotBlank()) {
            MPVLib.setPropertyString("http-header-fields", headerStr)
        }
        MPVLib.setPropertyDouble("speed", speed.toDouble())
        MPVLib.command(arrayOf("loadfile", s.url))
    }

    // When mpv is initialized, configure it and load the first stream.
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(initialized, selectedIndex) {
        if (!initialized) return@LaunchedEffect
        loadCurrent()
        // Resume from history
        val key = StormStore.historyKey(playback.item, playback.episode)
        val resumeSec = history.firstOrNull { it.key == key && !it.completed }?.let { it.positionMs / 1000 }
        if (resumeSec != null && resumeSec > 5) {
            delay(800)
            MPVLib.command(arrayOf("seek", resumeSec.toString(), "absolute"))
        }
    }

    // Rely on observed-property callbacks for state (see propertyObserver).

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        MPVPlayer(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { controlsVisible = !controlsVisible }) },
            onInitialized = {
                MPVLib.setPropertyString("vo", "gpu")
                MPVLib.setPropertyString("hwdec", "auto")
                MPVLib.setPropertyString("cache", "yes")
                MPVLib.setPropertyString("cache-secs", "10")
                MPVLib.setPropertyString("ytdl", "no")
                MPVLib.setPropertyBoolean("keep-open", true)
                // tls-verify off because mpv's Mbed TLS can't read Android CA store
                MPVLib.setPropertyString("tls-verify", "no")
                initialized = true
            },
            observedProperties = {
                long("duration")
                long("time-pos")
                boolean("pause")
                boolean("eof-reached")
            },
            propertyObserver = {
                long("duration") { durSec = it }
                long("time-pos") { posSec = it }
                boolean("pause") { isPaused = it }
                boolean("eof-reached") { eof -> isEof = eof }
            }
        )

        // Error
        errMsg?.let { err ->
            Surface(color = Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Playback error", color = Color.White, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(err, color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Row {
                        TextButton(onClick = { errMsg = null; loadCurrent() }) {
                            Text("Retry", color = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onBack) { Text("Back", color = Color.White) }
                    }
                }
            }
        }

        // Controls overlay
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(
                    Color.Black.copy(alpha = 0.6f), Color.Transparent, Color.Black.copy(alpha = 0.8f)))
            )) {
                // Top
                Row(Modifier.align(Alignment.TopStart).statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(playback.item.title, color = Color.White, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = playback.episode?.let { "S${it.season}:E${it.number} · ${it.title ?: ""}" }
                            ?: "libmpv"
                        Text(sub, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
                    }
                    SuggestionChip(onClick = {},
                        label = { Text("libmpv", color = Color.White, fontSize = 10.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color.White.copy(alpha = 0.15f)))
                }

                // Center controls
                Row(Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        MPVLib.command(arrayOf("seek", "-10"))
                    }) {
                        Icon(Icons.Default.Replay10, "Back 10s", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    Surface(color = Color.White.copy(alpha = 0.15f), shape = CircleShape,
                        modifier = Modifier.size(64.dp)) {
                        IconButton(onClick = {
                            MPVLib.setPropertyBoolean("pause", !isPaused)
                        }, modifier = Modifier.fillMaxSize()) {
                            Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = "Play/Pause", tint = Color.White,
                                modifier = Modifier.size(36.dp))
                        }
                    }
                    IconButton(onClick = { MPVLib.command(arrayOf("seek", "10")) }) {
                        Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White,
                            modifier = Modifier.size(36.dp))
                    }
                }

                // Bottom
                Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(posSec * 1000), color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = if (durSec > 0) (posSec.toFloat() / durSec).coerceIn(0f, 1f) else 0f,
                            onValueChange = {
                                MPVLib.command(arrayOf("seek", (it * durSec).toInt().toString(), "absolute"))
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White, activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(formatTime(durSec * 1000), color = Color.White, fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            speed = when { speed >= 2f -> 0.5f; else -> speed + 0.25f }
                            MPVLib.setPropertyDouble("speed", speed.toDouble())
                        }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                            Text("${speed}x", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                        IconButton(onClick = { MPVLib.command(arrayOf("cycle", "sub")) }) {
                            Icon(Icons.Default.ClosedCaption, "Subs", tint = Color.White,
                                modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = { MPVLib.command(arrayOf("cycle", "audio")) }) {
                            Icon(Icons.Default.Audiotrack, "Audio", tint = Color.White,
                                modifier = Modifier.size(22.dp))
                        }
                        if (streams.size > 1) {
                            Row(Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                streams.forEachIndexed { i, s ->
                                    FilterChip(selected = i == selectedIndex,
                                        onClick = { selectedIndex = i },
                                        label = { Text((s.quality ?: s.name).take(18), color = Color.White,
                                            style = MaterialTheme.typography.labelSmall) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                            containerColor = Color.White.copy(alpha = 0.12f)))
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
