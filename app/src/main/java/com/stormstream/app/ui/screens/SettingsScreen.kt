package com.stormstream.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.data.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel = viewModel(),
) {
    val adult by viewModel.adultEnabled.collectAsState()
    val providers by viewModel.providers.collectAsState()
    var showAbout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
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
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FlashOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "StormStream",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "v0.2.0 · universal streaming engine",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Playback & content",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
            }

            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingSwitch(
                            title = "Show adult content",
                            subtitle = "Include NSFW providers and titles in results",
                            checked = adult,
                            onChecked = { viewModel.setAdultEnabled(it) }
                        )
                        HorizontalDivider()
                        SettingAction(
                            title = "Refresh home",
                            subtitle = "Re-fetch catalogs from all enabled providers",
                            icon = Icons.Default.Refresh,
                            onClick = { viewModel.refreshHome() }
                        )
                        HorizontalDivider()
                        SettingAction(
                            title = "About StormStream",
                            subtitle = "Providers supported, version, credits",
                            icon = Icons.Default.Info,
                            onClick = { showAbout = true }
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Installed: ${providers.size} provider(s)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                val working = providers.values.count {
                    it.config.type in setOf(
                        com.stormstream.app.data.ProviderType.STREMIO,
                        com.stormstream.app.data.ProviderType.UNIVERSAL_SCRAPER,
                        com.stormstream.app.data.ProviderType.IPTV
                    )
                }
                val scaffolds = providers.size - working
                Text(
                    "$working fully working · $scaffolds plugin adapters (scaffold-only until Phase 3)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("About StormStream", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("StormStream v0.2.0", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("A clean-room universal streaming app for Android.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    val prows = listOf(
                        "Stremio addons" to "✅ Working",
                        "Universal HTML/JSON scrapers" to "✅ Working",
                        "IPTV / M3U playlists" to "✅ Working",
                        "CloudStream .cs3 plugins" to "🔌 Phase 3",
                        "Vega / QuickJS providers" to "🔌 Phase 3",
                        "SkyStream extensions" to "🔌 Phase 3",
                        "Sora / Aniyomi / Nuvio / Manga" to "🔌 Phase 3",
                        "Native .storm extensions" to "🔌 Phase 3",
                    )
                    prows.forEach { (name, status) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(name, style = MaterialTheme.typography.bodySmall)
                            Text(status, style = MaterialTheme.typography.labelSmall,
                                color = if (status.startsWith("✅"))
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SettingAction(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
