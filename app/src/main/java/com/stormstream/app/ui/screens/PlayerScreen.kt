package com.stormstream.app.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.StreamSource
import com.stormstream.app.data.StreamType

@kotlin.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: AppViewModel = viewModel(),
) {
    val context = LocalContext.current
    val streams by viewModel.streams.collectAsState()
    val item by viewModel.selectedItem.collectAsState()

    var selected by remember { mutableStateOf<StreamSource?>(streams.firstOrNull()) }
    LaunchedEffect(streams) { if (selected == null) selected = streams.firstOrNull() }

    // Build one ExoPlayer that lives for the lifetime of this screen.
    val player = remember {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("StormStream/0.1 (Linux; Android)")
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    DisposableEffect(selected) {
        val s = selected
        if (s != null) {
            val factory = DefaultHttpDataSource.Factory()
                .setUserAgent("StormStream/0.1 (Linux; Android)")
                .setDefaultRequestProperties(s.headers)
                .setAllowCrossProtocolRedirects(true)
            val uri = android.net.Uri.parse(s.url)
            val mediaItem = ExoMediaItem.Builder().setUri(uri).build()
            val source: MediaSource = when (s.type) {
                StreamType.HLS -> HlsMediaSource.Factory(factory).createMediaSource(mediaItem)
                StreamType.DASH -> DashMediaSource.Factory(factory).createMediaSource(mediaItem)
                StreamType.MP4, StreamType.MKV -> ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
                StreamType.SUBTITLE_ONLY, StreamType.UNKNOWN ->
                    ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
            }
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            player.release()
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
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
        }

        if (selected == null && streams.isEmpty()) {
            Text("No playable streams available for this item.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center))
        }

        if (streams.size > 1) {
            // Source switcher chip row
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 72.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                streams.take(8).forEach { s ->
                    FilterChip(
                        selected = selected?.url == s.url,
                        onClick = { selected = s },
                        label = {
                            Text((s.quality ?: s.name).take(24),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    )
                }
            }
        }

        Text(
            item?.title ?: "",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 56.dp, top = 12.dp, end = 16.dp)
        )
    }
}
