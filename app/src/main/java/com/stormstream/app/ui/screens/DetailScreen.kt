package com.stormstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stormstream.app.R
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.Episode
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.MediaType
import com.stormstream.app.data.StreamSource
import com.stormstream.app.ui.components.CenteredLoading
import com.stormstream.app.ui.components.QualityChip
import com.stormstream.app.ui.components.StreamPickerContent
import com.stormstream.app.ui.components.TypeBadge

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    viewModel: AppViewModel = viewModel(),
    onBack: () -> Unit,
    onPlayStart: () -> Unit = {},
) {
    val item by viewModel.selectedItem.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val streams by viewModel.streams.collectAsState()
    val streamsLoading by viewModel.streamsLoading.collectAsState()
    val detailLoading by viewModel.detailLoading.collectAsState()
    val selectedEpisode by viewModel.selectedEpisode.collectAsState()
    val currentProgress by viewModel.currentProgress.collectAsState()
    val seasons by viewModel.seasons.collectAsState()

    var showStreamPicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedSeason by remember { mutableStateOf<Int?>(null) }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                item == null || detailLoading -> CenteredLoading(modifier = Modifier.fillMaxSize())
                else -> {
                    val media = item!!
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 32.dp),
                    ) {
                        item(key = "hero") { DetailHero(media = media) }
                        item(key = "actions") {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            onPlayStart()
                                            viewModel.playSelected(openPicker = { showStreamPicker = true })
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (episodes.isEmpty()) "Play" else "Play episode")
                                    }
                                    val resumeProgress = currentProgress
                                    if (resumeProgress != null && resumeProgress.resumable) {
                                        OutlinedButton(
                                            onClick = {
                                                onPlayStart()
                                                viewModel.playSelected(openPicker = { showStreamPicker = true })
                                            },
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Resume")
                                        }
                                    }
                                }
                                if (streamsLoading) {
                                    Row(
                                        modifier = Modifier.padding(top = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Resolving streams…",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (media.cast.isNotEmpty()) {
                            item(key = "cast") {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    Text(
                                        text = "Cast",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = media.cast.joinToString(" · "),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (media.description != null || media.genres.isNotEmpty()) {
                            item(key = "meta") {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    if (media.genres.isNotEmpty()) {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            media.genres.take(6).forEach { genre ->
                                                QualityChip(genre.uppercase())
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    if (media.description != null) {
                                        Text(
                                            text = media.description,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (episodes.isNotEmpty()) {
                            item(key = "episodes-header") {
                                Text(
                                    text = "Episodes",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            if (seasons.size > 1) {
                                item(key = "seasons") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        items(seasons) { season ->
                                            FilterChip(
                                                selected = selectedSeason == season,
                                                onClick = { selectedSeason = if (selectedSeason == season) null else season },
                                                label = { Text("Season $season") },
                                            )
                                        }
                                    }
                                }
                            }
                            val visibleEpisodes = if (selectedSeason != null) {
                                episodes.filter { it.season == selectedSeason }
                            } else episodes
                            items(visibleEpisodes, key = { it.id }) { ep ->
                                EpisodeRow(
                                    episode = ep,
                                    selected = selectedEpisode?.id == ep.id,
                                    onClick = { viewModel.selectEpisode(ep) },
                                    onPlay = {
                                        onPlayStart()
                                        viewModel.selectEpisode(ep, autoPlay = true)
                                    },
                                )
                            }
                        } else if (streams.isNotEmpty()) {
                            item(key = "streams") {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(
                                        text = "Streams",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    streams.forEach { source ->
                                        StreamRow(
                                            source = source,
                                            onClick = {
                                                onPlayStart()
                                                viewModel.playSource(media, null, source)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
        }
    }

    if (showStreamPicker) {
        ModalBottomSheet(
            onDismissRequest = { showStreamPicker = false },
            sheetState = sheetState,
        ) {
            StreamPickerContent(
                streams = streams,
                providerName = { viewModel.providerName(it) },
                onSelect = { source ->
                    showStreamPicker = false
                    val media = item
                    val ep = if (episodes.isNotEmpty()) {
                        viewModel.selectedEpisode.value ?: episodes.firstOrNull()
                    } else null
                    if (media != null) {
                        onPlayStart()
                        viewModel.playSource(media, ep, source)
                    }
                },
            )
        }
    }
}

@Composable
private fun DetailHero(media: MediaItem) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
    ) {
        AsyncImage(
            model = media.backdropUrl ?: media.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.ic_placeholder),
            error = painterResource(R.drawable.ic_placeholder),
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.9f))
                    )
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            Text(
                text = media.title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(
                    when (media.type) {
                        MediaType.MOVIE -> "MOVIE"
                        MediaType.SERIES -> "SERIES"
                        MediaType.ANIME -> "ANIME"
                        MediaType.IPTV, MediaType.LIVE -> "LIVE TV"
                        MediaType.MANGA -> "MANGA"
                        MediaType.UNKNOWN -> "VIDEO"
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = listOfNotNull(
                        media.year?.toString(),
                        media.runtime,
                        media.rating?.let { "★ ${"%.1f".format(it)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    selected: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    androidx.compose.material3.Surface(
        color = container,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .padding(end = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (episode.thumbnailUrl != null) {
                AsyncImage(
                    model = episode.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(R.drawable.ic_placeholder),
                    error = painterResource(R.drawable.ic_placeholder),
                    modifier = Modifier
                        .width(96.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "S${episode.season} E${episode.number}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = episode.title ?: "Episode ${episode.number}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play episode")
            }
        }
    }
}

@Composable
private fun StreamRow(source: StreamSource, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.quality ?: source.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${source.name} · ${source.type.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClick) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play stream")
            }
        }
    }
}
