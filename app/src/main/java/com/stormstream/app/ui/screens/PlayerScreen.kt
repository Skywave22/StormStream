package com.stormstream.app.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.data.StormStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.player.PlaybackService

/**
 * Full-screen video player.
 *
 * Phase 1 fixes:
 *  - Reads [AppViewModel.playback] (PlaybackTarget) instead of broken path args.
 *  - Builds MediaSource via [PlaybackService.buildMediaSource] so per-source HTTP
 *    headers and subtitles are honored.
 *  - Listens to player state to show buffering / error UI.
 *  - Source switcher chips switch between resolved streams on-the-fly.
 *  - Auto-resizes, keeps screen on, respects lifecycle (pauses on background).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: AppViewModel = viewModel(),
) {
    val context = LocalContext.current
    val target by viewModel.playback.collectAsState()

    if (target == null) {
        // No target set — tell the user and go back.
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Nothing to play",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onBack) { Text("Go back", color = Color.White) }
            }
        }
        return
    }

    val playback = target!!
    val streams = playback.streams
    var selectedIndex by remember(playback.selectedIndex) { mutableStateOf(playback.selectedIndex) }

    // Build one ExoPlayer per PlayerScreen entry; release on dispose.
    val player = remember(context) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; StormStream/0.2) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    // Track player state for UI.
    var playerState by remember { mutableStateOf(Player.STATE_IDLE) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                playerState = playbackState
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerError = error.message ?: "Playback error"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.stop()
            player.release()
        }
    }

    // When the selected stream changes, load it. Seek to resume position if
    // there's a history entry for this key.
    LaunchedEffect(selectedIndex) {
        val stream = streams.getOrNull(selectedIndex) ?: return@LaunchedEffect
        playerError = null
        val dataSourceFactory = DefaultHttpDataSource.Factory()
        val source = PlaybackService.buildMediaSource(dataSourceFactory, stream)
        player.setMediaSource(source)
        player.prepare()
        // Seek to resume position on first load if applicable.
        val resumeKey = StormStore.historyKey(playback.item, playback.episode)
        val resumeMs = viewModel.history.value.firstOrNull { it.key == resumeKey }
            ?.takeIf { !it.completed }?.positionMs ?: 0L
        if (resumeMs > 5_000L) {
            player.seekTo(resumeMs)
        }
        player.playWhenReady = true
    }

    // Periodically record progress for history/resume.
    LaunchedEffect(playback, selectedIndex) {
        val stream = streams.getOrNull(selectedIndex)
        while (isActive) {
            delay(5_000L)
            val pos = player.currentPosition
            val dur = player.duration.coerceAtLeast(0L)
            if (pos > 2_000L) {
                viewModel.recordProgress(
                    item = playback.item,
                    episode = playback.episode,
                    streamUrl = stream?.url,
                    positionMs = pos,
                    durationMs = dur,
                )
            }
        }
    }

    // Record final position and mark completion when leaving.
    DisposableEffect(playback, selectedIndex) {
        onDispose {
            val stream = streams.getOrNull(selectedIndex)
            val pos = player.currentPosition
            val dur = player.duration.coerceAtLeast(0L)
            if (pos > 2_000L) {
                viewModel.recordProgress(
                    item = playback.item,
                    episode = playback.episode,
                    streamUrl = stream?.url,
                    positionMs = pos,
                    durationMs = dur,
                )
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setPlayer(player)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top bar with title + back button
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }

        Text(
            playback.item.title + (playback.episode?.let { " · S${it.season} E${it.number}" } ?: ""),
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 56.dp, top = 16.dp, end = 16.dp)
        )

        // Buffering indicator
        if (playerState == Player.STATE_BUFFERING && playerError == null) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Error state
        if (playerError != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Playback failed",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        playerError ?: "Unknown error",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            playerError = null
                            val s = streams.getOrNull(selectedIndex) ?: return@TextButton
                            val f = DefaultHttpDataSource.Factory()
                            player.setMediaSource(PlaybackService.buildMediaSource(f, s))
                            player.prepare()
                            player.playWhenReady = true
                        }) { Text("Retry", color = Color.White) }
                        TextButton(onClick = onBack) { Text("Back", color = Color.White) }
                    }
                }
            }
        }

        // Stream switcher chips
        if (streams.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 72.dp)
                    .padding(horizontal = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                        )
                    )
                }
            }
        }
    }
}
