package com.stormstream.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.Episode
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.StreamSource

@Composable
fun DetailScreen(
    onPlay: (MediaItem, Episode?) -> Unit,
    viewModel: AppViewModel = viewModel(),
) {
    val item by viewModel.selectedItem.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val streams by viewModel.streams.collectAsState()
    var selectedEpisode by remember { mutableStateOf<Episode?>(null) }

    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val media = item!!

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            // Backdrop
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            ) {
                if (media.backdropUrl != null) {
                    AsyncImage(
                        model = media.backdropUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (media.posterUrl != null) {
                    AsyncImage(
                        model = media.posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Surface(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxSize()
                ) {}
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(media.title, style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground)
                    val meta = listOfNotNull(
                        media.year?.toString(),
                        media.rating?.let { "★ ${"%.1f".format(it)}" },
                        media.type.name,
                    ).joinToString(" · ")
                    Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (media.posterUrl != null && media.type != com.stormstream.app.data.MediaType.IPTV) {
                    AsyncImage(
                        model = media.posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(110.dp).aspectRatio(2f/3f)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
                Column(Modifier.weight(1f)) {
                    if (!media.description.isNullOrBlank()) {
                        Text(media.description, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (media.type == com.stormstream.app.data.MediaType.IPTV) {
                        Button(
                            onClick = { onPlay(media, null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Play channel")
                        }
                    } else if (episodes.isEmpty() || media.type == com.stormstream.app.data.MediaType.MOVIE) {
                        Button(
                            onClick = { onPlay(media, null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Play")
                        }
                        Spacer(Modifier.height(8.dp))
                        StreamList(streams = streams)
                    }
                }
            }
        }
        if (episodes.isNotEmpty()) {
            item {
                Text("Episodes", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
            }
            items(episodes, key = { it.id }) { ep ->
                EpisodeRow(
                    episode = ep,
                    selected = selectedEpisode?.id == ep.id,
                    onClick = {
                        selectedEpisode = ep
                        viewModel.loadStreams(media, ep)
                    },
                    onPlay = { onPlay(media, ep) }
                )
            }
            if (selectedEpisode != null) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Streams", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    StreamList(streams = streams, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onPlay(media, selectedEpisode) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        enabled = streams.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Play selected episode")
                    }
                    Spacer(Modifier.height(24.dp))
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
    onPlay: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("S${episode.season} E${episode.number}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(episode.title ?: "Episode ${episode.number}",
                    style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
            }
        }
    }
}

@Composable
private fun StreamList(streams: List<StreamSource>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        streams.forEach { s ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.bodySmall)
                            Text(s.type.name + (s.quality?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
            }
        }
        if (streams.isEmpty()) {
            Text("No streams resolved — this may be a scaffold-only provider.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
