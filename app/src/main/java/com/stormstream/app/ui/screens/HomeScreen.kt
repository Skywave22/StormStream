package com.stormstream.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.MediaItem
import com.stormstream.app.ui.components.ContentRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AppViewModel = viewModel(),
    onItemClick: (MediaItem) -> Unit,
) {
    val home by viewModel.homeState.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("StormStream", style = MaterialTheme.typography.headlineSmall)
                        Text("One player, every provider.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 80.dp),
            ) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text("Welcome to StormStream",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.height(4.dp))
                            Text("Add Stremio addons, universal scrapers, CloudStream plugins, " +
                                    "Vega providers, M3U playlists and more — all in one player.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                items(home.keys.toList(), key = { it.catalogId + "@" + it.providerId }) { ref: CatalogRef ->
                    val rows = home[ref].orEmpty()
                    if (rows.isNotEmpty()) {
                        ContentRow(
                            title = ref.name,
                            subtitle = providerLabel(ref),
                            items = rows,
                            onItemClick = onItemClick,
                        )
                    }
                }
                if (home.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No providers installed yet — open the Extensions tab to add sources.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
