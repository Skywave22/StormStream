package com.stormstream.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.HistoryEntry
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.MediaType
import com.stormstream.app.ui.components.ContinueWatchingRow
import com.stormstream.app.ui.components.ContentRow
import com.stormstream.app.ui.components.HeroCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AppViewModel = viewModel(),
    onItemClick: (MediaItem) -> Unit,
) {
    val home by viewModel.homeState.collectAsState()
    val loading by viewModel.homeLoading.collectAsState()
    val continueItems by viewModel.continueWatching.collectAsState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FlashOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "StormStream",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "One player, every provider.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshHome() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (loading && home.isEmpty() && continueItems.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Loading your providers…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp, top = 8.dp),
                ) {
                    // Hero carousel of first few featured items
                    val allItems = home.values.flatten()
                    if (allItems.isNotEmpty()) {
                        item {
                            HeroCard(
                                items = allItems.shuffled().take(8),
                                onItemClick = onItemClick,
                                onPlayClick = { m ->
                                    // Play click: open detail and autostart will kick in for movies.
                                    onItemClick(m)
                                }
                            )
                        }
                    } else if (!loading) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                shape = MaterialTheme.shapes.extraLarge,
                            ) {
                                Row(
                                    Modifier.padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.FlashOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Welcome to StormStream",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            "Stremio addons, universal scrapers, M3U playlists — all in one.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Continue watching row
                    if (continueItems.isNotEmpty()) {
                        item {
                            ContinueWatchingRow(
                                title = "Continue watching",
                                items = continueItems,
                                onItemClick = { entry ->
                                    // Re-open item so detail screen loads; VM will have streams.
                                    viewModel.openItem(entry.item)
                                    onItemClick(entry.item)
                                },
                                onRemove = { entry ->
                                    viewModel.removeFromHistory(entry.key)
                                }
                            )
                        }
                    }

                    // Catalog rows
                    items(home.keys.toList(), key = { it.catalogId + "@" + it.providerId }) { ref ->
                        val rows = home[ref].orEmpty()
                        if (rows.isNotEmpty()) {
                            val progressMap = remember(rows) {
                                // Attach any continue-watching progress
                                val map = mutableMapOf<String, Float>()
                                continueItems.forEach { h ->
                                    map["${h.item.providerId}:${h.item.id}"] = h.progress
                                }
                                map
                            }
                            ContentRow(
                                title = ref.name,
                                subtitle = providerLabel(ref),
                                items = rows,
                                onItemClick = onItemClick,
                                progressMap = progressMap,
                            )
                        }
                    }

                    if (home.isEmpty() && !loading) {
                        item {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "No sources installed yet",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Open the Extensions tab to add Stremio addons, IPTV playlists, or scrapers.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
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
private fun providerLabel(ref: CatalogRef): String {
    val pid = ref.providerId
    return when {
        pid.startsWith("stremio:") -> "Stremio · ${pid.removePrefix("stremio:")}"
        pid.startsWith("scraper:") -> "Scraper · ${pid.removePrefix("scraper:")}"
        pid.startsWith("iptv:") -> "IPTV · ${pid.removePrefix("iptv:")}"
        pid.startsWith("cs3:") -> "CloudStream"
        pid.startsWith("vega:") -> "Vega"
        pid.startsWith("skystream:") -> "SkyStream"
        pid.startsWith("sora:") -> "Sora"
        pid.startsWith("aniyomi:") -> "Aniyomi"
        pid.startsWith("nuvio:") -> "Nuvio"
        pid.startsWith("manga:") -> "Manga"
        pid.startsWith("storm:") -> "Storm Extension"
        else -> pid
    }
}
