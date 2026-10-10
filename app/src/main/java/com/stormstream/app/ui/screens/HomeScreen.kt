package com.stormstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.stormstream.app.data.HomeRow
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.MediaType
import com.stormstream.app.ui.components.ContentRow
import com.stormstream.app.ui.components.EmptyState
import com.stormstream.app.ui.components.ShimmerRow
import com.stormstream.app.ui.components.TypeBadge
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Surface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AppViewModel = viewModel(),
    onItemClick: (MediaItem) -> Unit,
    onBrowseCatalog: (com.stormstream.app.data.CatalogRef) -> Unit,
    onOpenExtensions: () -> Unit,
) {
    val rows by viewModel.homeRows.collectAsState()
    val refreshing by viewModel.homeRefreshing.collectAsState()
    val continueWatching by viewModel.continueWatching.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_storm_logo),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("StormStream")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshHome() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refreshHome() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                // Continue watching shelf.
                if (continueWatching.isNotEmpty()) {
                    item(key = "continue-watching") {
                        Column {
                            Text(
                                text = "Continue watching",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(continueWatching, key = { it.itemKey }) { progress ->
                                    ContinueWatchingCard(
                                        progress = progress,
                                        onClick = {
                                            viewModel.openItem(
                                                MediaItem(
                                                    id = progress.itemKey.substringAfter('|'),
                                                    providerId = progress.providerId,
                                                    title = progress.title,
                                                    type = MediaType.MOVIE,
                                                    posterUrl = progress.posterUrl,
                                                )
                                            )
                                            onItemClick(
                                                MediaItem(
                                                    id = progress.itemKey.substringAfter('|'),
                                                    providerId = progress.providerId,
                                                    title = progress.title,
                                                    type = MediaType.MOVIE,
                                                    posterUrl = progress.posterUrl,
                                                )
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Hero: first item with a backdrop.
                val hero = rows.firstOrNull { it.items.isNotEmpty() }
                    ?.items?.firstOrNull { it.backdropUrl != null || it.posterUrl != null }
                if (hero != null) {
                    item(key = "hero") {
                        HeroCard(
                            item = hero,
                            providerName = viewModel.providerName(hero.providerId),
                            onPlay = { onItemClick(hero) },
                            onInfo = { onItemClick(hero) },
                        )
                    }
                }

                val loading = rows.any { it.isLoading }
                if (rows.isEmpty() && !loading) {
                    item(key = "empty") {
                        EmptyState(
                            title = "No sources yet",
                            message = "Add a Stremio addon, an IPTV playlist, a scraper or a JS plugin to start watching.",
                            actionText = "Add sources",
                            onAction = onOpenExtensions,
                            modifier = Modifier.padding(top = 80.dp),
                        )
                    }
                }

                items(
                    items = rows.filter { it.items.isNotEmpty() || it.isLoading || it.error != null },
                    key = { it.catalog.providerId + "@" + it.catalog.catalogId },
                ) { row: HomeRow ->
                    when {
                        row.isLoading -> ShimmerRow()
                        row.error != null -> {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    text = row.catalog.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Text(
                                    text = row.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        else -> ContentRow(
                            title = row.catalog.name,
                            subtitle = viewModel.providerName(row.catalog.providerId),
                            items = row.items,
                            onItemClick = onItemClick,
                            onSeeAll = { onBrowseCatalog(row.catalog) },
                            onHideRow = { viewModel.hideCatalog(row.catalog) },
                            providerName = { viewModel.providerName(it.providerId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    item: MediaItem,
    providerName: String,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .padding(16.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onInfo),
    ) {
        AsyncImage(
            model = item.backdropUrl ?: item.posterUrl,
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
                        colors = listOf(
                            Color.Black.copy(alpha = 0.1f),
                            Color.Black.copy(alpha = 0.85f),
                        ),
                    )
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp),
        ) {
            TypeBadge(
                when (item.type) {
                    MediaType.MOVIE -> "MOVIE"
                    MediaType.SERIES -> "SERIES"
                    MediaType.ANIME -> "ANIME"
                    MediaType.IPTV, MediaType.LIVE -> "LIVE TV"
                    MediaType.MANGA -> "MANGA"
                    MediaType.UNKNOWN -> "VIDEO"
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    item.year?.toString(),
                    item.rating?.let { "★ ${"%.1f".format(it)}" },
                    providerName,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }
                FilledTonalButton(onClick = onInfo) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Details")
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    progress: com.stormstream.app.data.WatchProgress,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (progress.posterUrl != null) {
                AsyncImage(
                    model = progress.posterUrl,
                    contentDescription = progress.title,
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(R.drawable.ic_placeholder),
                    error = painterResource(R.drawable.ic_placeholder),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Progress bar at the bottom of the poster.
            LinearProgressIndicator(
                progress = { progress.fraction.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Black.copy(alpha = 0.5f),
            )
            // Resume badge.
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            ) {
                Text(
                    text = formatDuration(progress.positionSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = progress.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "${h}h ${m}m left" else "${m}m left"
}
