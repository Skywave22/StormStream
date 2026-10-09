package com.stormstream.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onRefresh: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row {
                            Icon(Icons.Default.Bolt, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("StormStream", style = MaterialTheme.typography.titleMedium)
                                Text("v0.1.0 · universal provider engine",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                Text("Provider systems supported",
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                val providers = listOf(
                    "Stremio addons (manifest.json, catalogs/meta/streams/subtitles)" to "working",
                    "Universal HTML/JSON scrapers (CSS selectors, no-code config)" to "working",
                    "IPTV / M3U playlists (grouped channel catalogs)" to "working",
                    "CloudStream .cs3 plugins" to "adapter scaffold",
                    "Vega providers (CommonJS modules)" to "adapter scaffold",
                    "SkyStream extensions" to "adapter scaffold",
                    "Sora extensions" to "adapter scaffold",
                    "Aniyomi extensions" to "adapter scaffold",
                    "Nuvio JS/QuickJS scrapers" to "adapter scaffold",
                    "Manga sources" to "adapter scaffold",
                    "Native Storm (.storm) extensions" to "adapter scaffold",
                )
                providers.forEach { (name, status) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        Surface(color = if (status == "working")
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small) {
                            Text(status,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item {
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh home")
                }
            }
        }
    }
}
