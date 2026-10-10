package com.stormstream.app.ui.screens

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.player.StormMpvView
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.player.TrackInfo
import kotlin.math.abs
import kotlin.math.roundToInt

private fun formatTime(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Mutable holder for in-progress gesture state. */
private class GestureState {
    var mode: Int = MODE_NONE
    var startX = 0f
    var startY = 0f
    var startPosition = 0.0
    var startBrightness = 0.5f
    var startVolume = 0
    var seekTarget = 0.0
    var brightness = 0.5f
    var volume = 0

    companion object {
        const val MODE_NONE = 0
        const val MODE_SEEK = 1
        const val MODE_BRIGHTNESS = 2
        const val MODE_VOLUME = 3
    }
}

/**
 * The player screen. Video is rendered by the app's single internal libmpv
 * instance ([com.stormstream.app.player.MpvPlayerController]) into a
 * [StormMpvView] surface; all controls are custom Compose UI on top.
 *
 * Gestures: tap = show/hide controls, double-tap = seek ±10s,
 * horizontal drag = seek, vertical drag (left third) = brightness,
 * vertical drag (right two-thirds) = volume.
 */
@Composable
fun PlayerScreen(
    viewModel: AppViewModel = viewModel(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val player = viewModel.player

    val isPlaying by player.isPlaying.collectAsState()
    val position by player.position.collectAsState()
    val duration by player.duration.collectAsState()
    val isBuffering by player.isBuffering.collectAsState()
    val speed by player.speed.collectAsState()
    val audioTracks by player.audioTracks.collectAsState()
    val subTracks by player.subTracks.collectAsState()
    val subVisible by player.subtitlesVisible.collectAsState()
    val error by player.error.collectAsState()
    val hasEpisodes by viewModel.episodes.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0f) }
    var osdText by remember { mutableStateOf<String?>(null) }
    val gesture = remember { GestureState() }

    var showAudioMenu by remember { mutableStateOf(false) }
    var showSubMenu by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // Auto-hide the controls after a few seconds.
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            kotlinx.coroutines.delay(3500)
            controlsVisible = false
        }
    }

    // Fade the OSD text out.
    LaunchedEffect(osdText) {
        if (osdText != null) {
            kotlinx.coroutines.delay(1200)
            osdText = null
        }
    }

    // Immersive fullscreen while the player is open.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val controller = activity?.let {
            WindowCompat.getInsetsController(it.window, view)
        }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun showOsd(text: String) {
        osdText = text
        controlsVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .keepScreenOn(),
    ) {
        // ---------- libmpv video surface ----------
        AndroidView(
            factory = { ctx ->
                player.getOrCreateView(ctx)
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            val delta = if (offset.x < size.width / 2) -10.0 else 10.0
                            player.seekBy(delta)
                            showOsd(if (delta < 0) "⏪ 10s" else "10s ⏩")
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            gesture.mode = GestureState.MODE_NONE
                            gesture.startX = offset.x
                            gesture.startY = offset.y
                            gesture.startPosition = position
                            gesture.startBrightness = run {
                                val lp = activity?.window?.attributes
                                val b = lp?.screenBrightness ?: -1f
                                if (b in 0f..1f) b else 0.5f
                            }
                            gesture.startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        },
                        onDrag = { change, dragAmount ->
                            if (gesture.mode == GestureState.MODE_NONE) {
                                val dx = abs(dragAmount.x)
                                val dy = abs(dragAmount.y)
                                if (dx + dy < 24f) return@detectDragGestures
                                gesture.mode = when {
                                    dx > dy -> GestureState.MODE_SEEK
                                    gesture.startX < size.width / 3f -> GestureState.MODE_BRIGHTNESS
                                    else -> GestureState.MODE_VOLUME
                                }
                            }
                            when (gesture.mode) {
                                GestureState.MODE_SEEK -> {
                                    val totalDx = change.position.x - gesture.startX
                                    gesture.seekTarget =
                                        (gesture.startPosition + totalDx / size.width * duration)
                                            .coerceIn(0.0, duration.coerceAtLeast(1.0))
                                    showOsd(
                                        "${formatTime(gesture.seekTarget)} / ${formatTime(duration)}"
                                    )
                                }
                                GestureState.MODE_BRIGHTNESS -> {
                                    gesture.brightness =
                                        (gesture.startBrightness + (gesture.startY - change.position.y) / size.height * 1.2f)
                                            .coerceIn(0.02f, 1f)
                                    val lp = activity?.window?.attributes ?: return@detectDragGestures
                                    lp.screenBrightness = gesture.brightness
                                    activity?.window?.attributes = lp
                                    showOsd("Brightness ${(gesture.brightness * 100).roundToInt()}%")
                                }
                                GestureState.MODE_VOLUME -> {
                                    gesture.volume =
                                        (gesture.startVolume + (gesture.startY - change.position.y) / size.height * maxVolume)
                                            .roundToInt()
                                            .coerceIn(0, maxVolume)
                                    audioManager.setStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        gesture.volume,
                                        0,
                                    )
                                    showOsd("Volume ${(gesture.volume * 100f / maxVolume.coerceAtLeast(1)).roundToInt()}%")
                                }
                            }
                        },
                        onDragEnd = {
                            if (gesture.mode == GestureState.MODE_SEEK) {
                                player.seekTo(gesture.seekTarget)
                            }
                            gesture.mode = GestureState.MODE_NONE
                        },
                        onDragCancel = { gesture.mode = GestureState.MODE_NONE },
                    )
                },
        )

        // ---------- buffering ----------
        if (isBuffering && error == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp),
                color = Color.White,
            )
        }

        // ---------- error ----------
        if (error != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = error ?: "Playback error",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = onBack) { Text("Go back") }
            }
        }

        // ---------- OSD ----------
        if (osdText != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            ) {
                Text(
                    text = osdText ?: "",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }

        // ---------- controls ----------
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Top gradient + bar
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.75f),
                                    Color.Transparent,
                                )
                            )
                        )
                        .padding(16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = player.title.ifBlank { "Now playing" },
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val source = player.currentSource
                            if (source != null) {
                                Text(
                                    text = listOfNotNull(
                                        source.quality,
                                        source.name,
                                    ).joinToString(" · "),
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // Bottom gradient + seek + buttons
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.85f),
                                )
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    // Seek bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = formatTime(if (scrubbing) scrubValue.toDouble() else position),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = if (scrubbing) scrubValue else position.toFloat(),
                            onValueChange = { value ->
                                scrubbing = true
                                scrubValue = value
                            },
                            onValueChangeFinished = {
                                player.seekTo(scrubValue.toDouble())
                                scrubbing = false
                            },
                            valueRange = 0f..duration.coerceAtLeast(1.0).toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatTime(duration),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        // Previous episode
                        IconButton(
                            onClick = { viewModel.playAdjacentEpisode(-1) },
                            enabled = hasEpisodes.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous episode",
                                tint = if (hasEpisodes.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.35f),
                            )
                        }

                        // Play / pause
                        IconButton(onClick = { player.togglePause() }) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp),
                            )
                        }

                        // Next episode
                        IconButton(
                            onClick = { viewModel.playAdjacentEpisode(1) },
                            enabled = hasEpisodes.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next episode",
                                tint = if (hasEpisodes.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.35f),
                            )
                        }

                        // Subtitles menu
                        Box {
                            IconButton(onClick = { showSubMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.ClosedCaption,
                                    contentDescription = "Subtitles",
                                    tint = if (subVisible) Color.White else Color.White.copy(alpha = 0.5f),
                                )
                            }
                            DropdownMenu(
                                expanded = showSubMenu,
                                onDismissRequest = { showSubMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (subVisible) "Hide subtitles" else "Show subtitles") },
                                    onClick = {
                                        player.toggleSubtitles()
                                        showSubMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Subtitles off") },
                                    onClick = {
                                        player.selectSubTrack(null)
                                        showSubMenu = false
                                    },
                                )
                                subTracks.forEach { track ->
                                    SubMenuItem(track, player) { showSubMenu = false }
                                }
                                if (subTracks.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No subtitle tracks", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                        onClick = {},
                                        enabled = false,
                                    )
                                }
                            }
                        }

                        // Audio menu
                        Box {
                            IconButton(onClick = { showAudioMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.AudioFile,
                                    contentDescription = "Audio track",
                                    tint = Color.White,
                                )
                            }
                            DropdownMenu(
                                expanded = showAudioMenu,
                                onDismissRequest = { showAudioMenu = false },
                            ) {
                                audioTracks.forEach { track ->
                                    AudioMenuItem(track, player) { showAudioMenu = false }
                                }
                                if (audioTracks.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No audio tracks", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                        onClick = {},
                                        enabled = false,
                                    )
                                }
                            }
                        }

                        // Speed menu
                        Box {
                            IconButton(onClick = { showSpeedMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "Playback speed",
                                    tint = Color.White,
                                )
                            }
                            DropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = { showSpeedMenu = false },
                            ) {
                                listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0).forEach { s ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${"%.2f".format(s).removeSuffix(".00")}x" +
                                                    if (abs(s - speed) < 0.01) "  ✓" else ""
                                            )
                                        },
                                        onClick = {
                                            player.setSpeed(s)
                                            showSpeedMenu = false
                                        },
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

@Composable
private fun SubMenuItem(
    track: TrackInfo,
    player: com.stormstream.app.player.MpvPlayerController,
    onSelected: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(track.label + if (track.selected) "  ✓" else "") },
        onClick = {
            player.selectSubTrack(track.id)
            onSelected()
        },
    )
}

@Composable
private fun AudioMenuItem(
    track: TrackInfo,
    player: com.stormstream.app.player.MpvPlayerController,
    onSelected: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(track.label + if (track.selected) "  ✓" else "") },
        onClick = {
            player.selectAudioTrack(track.id)
            onSelected()
        },
    )
}
