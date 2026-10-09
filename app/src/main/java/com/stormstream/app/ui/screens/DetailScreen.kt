package com.stormstream.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.Episode
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.MediaType
import com.stormstream.app.data.StreamSource

@Composable
fun DetailScreen(
    onPlay: (MediaItem, Episode?, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: AppViewModel = viewModel(),
) {
    val item by viewModel.selectedItem.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val streams by viewModel.streams.collectAsState()
    val detailLoading by viewModel.detailLoading.collectAsState()
    val streamsLoading by viewModel.streamsLoading.collectAsState()

    var selectedEpisode by remember { mutableStateOf<Episode?>(null) }
    var selectedStreamIndex by remember { mutableStateOf(0) }

    // When a new episode is selected, load its streams.
    LaunchedEffect(selectedEpisode) {
        val media = item ?: return@LaunchedEffect
        if (selectedEpisode != null && streams.isEmpty()) {
            viewModel.loadStreams(media, selectedEpisode)
        }
    }

    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val media = item!!
    val isSeries = episodes.isNotEmpty() && media.type in setOf(MediaType.SERIES, MediaType.ANIME)

    // Group episodes by season for nicer UX if there are multiple seasons.
    val seasons = remember(episodes) {
        episodes.groupBy { it.season }.toSortedMap()
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Backdrop + back button
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                val ctx = LocalContext.current
                if (media.backdropUrl != null || media.posterUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx)
                            .data(media.backdropUrl ?: media.posterUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // Bottom gradient so the title is readable over any image.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                                startY = 120f
                            )
                        )
                )
                // Top scrim for back button
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent)
                            )
                        )
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }
        }

        // Poster + title + meta row
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .offset(y = (-40).dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (media.posterUrl != null && media.type != MediaType.IPTV) {
                    AsyncImage(
                        model = media.posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(120.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(top = if (media.posterUrl != null && media.type != MediaType.IPTV) 50.dp else 0.dp)
                ) {
                    Text(
                        media.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    val meta = listOfNotNull(
                        media.year?.toString(),
                        media.rating?.let { "★ ${"%.1f".format(it)}" },
                        media.type.name.lowercase().replaceFirstChar { it.uppercase() },
                        media.genres.take(3).joinToString(", ").ifBlank { null }
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // Description + Play button
        item {
            Column(
                Modifier.padding(horizontal = 16.dp)
            ) {
                if (!media.description.isNullOrBlank()) {
                    Text(
                        media.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (detailLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                }
                if (media.type == MediaType.IPTV) {
                    Button(
                        onClick = { onPlay(media, null, 0) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Play channel")
                    }
                    Spacer(Modifier.height(8.dp))
                    StreamList(
                        streams = streams,
                        selectedIndex = selectedStreamIndex,
                        onSelect = { selectedStreamIndex = it },
                        loading = streamsLoading,
                    )
                } else if (!isSeries) {
                    // Movie
                    Button(
                        onClick = { onPlay(media, null, selectedStreamIndex) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = streams.isNotEmpty() && !streamsLoading,
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (streams.isNotEmpty()) "Play" else "Loading streams…")
                    }
                    Spacer(Modifier.height(8.dp))
                    StreamList(
                        streams = streams,
                        selectedIndex = selectedStreamIndex,
                        onSelect = { selectedStreamIndex = it },
                        loading = streamsLoading,
                    )
                }
            }
        }

        // Seasons + episodes
        if (isSeries) {
            seasons.forEach { (seasonNum, eps) ->
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Season $seasonNum",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(eps, key = { it.id }) { ep ->
                    EpisodeRow(
                        episode = ep,
                        selected = selectedEpisode?.id == ep.id,
                        onClick = {
                            selectedEpisode = ep
                            viewModel.loadStreams(media, ep)
                        }
                    )
                }
            }
            if (selectedEpisode != null) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Streams — S${selectedEpisode!!.season} E${selectedEpisode!!.number}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            StreamList(
                                streams = streams,
                                selectedIndex = selectedStreamIndex,
                                onSelect = { selectedStreamIndex = it },
                                loading = streamsLoading,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { onPlay(media, selectedEpisode, selectedStreamIndex) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = streams.isNotEmpty() && !streamsLoading,
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Play episode")
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (episode.thumbnailUrl != null) {
                AsyncImage(
                    model = episode.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(120.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "S${episode.season} E${episode.number}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    episode.title ?: "Episode ${episode.number}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
                if (!episode.description.isNullOrBlank()) {
                    Text(
                        episode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamList(
    streams: List<StreamSource>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (loading && streams.isEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    "Resolving streams…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (streams.isEmpty() && !loading) {
            Text(
                "No playable streams found for this item.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            streams.forEachIndexed { i, s ->
                val selected = i == selectedIndex
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(i) }
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                s.type.name + (s.quality?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (s.subtitles.isNotEmpty()) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("${s.subtitles.size} sub",
                                    style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        }
    }
}


