package com.stormstream.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.ProviderType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel = viewModel(),
) {
    val adult by viewModel.adultEnabled.collectAsState()
    val incognito by viewModel.incognitoEnabled.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val history by viewModel.history.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    var showAbout by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }

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
                    "Content & privacy",
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
                            onChecked = { viewModel.setAdultEnabled(it); viewModel.refreshHome() }
                        )
                        HorizontalDivider()
                        SettingSwitch(
                            title = "Incognito mode",
                            subtitle = "Don't save watch history while enabled",
                            checked = incognito,
                            onChecked = { viewModel.setIncognito(it) },
                            icon = if (incognito) Icons.Default.VisibilityOff
                                   else Icons.Default.Visibility
                        )
                        HorizontalDivider()
                        SettingAction(
                            title = "Refresh home",
                            subtitle = "Re-fetch catalogs from all enabled providers",
                            icon = Icons.Default.Refresh,
                            onClick = { viewModel.refreshHome() }
                        )
                    }
                }
            }

            item {
                Text(
                    "Data",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
            }

            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingAction(
                            title = "Clear watch history",
                            subtitle = "${history.size} item(s) saved",
                            icon = Icons.Default.DeleteSweep,
                            onClick = { confirmClearHistory = true }
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Installed: ${providers.size} provider(s) · " +
                    "${history.size} history · ${bookmarks.size} bookmarked",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                val working = providers.values.count {
                    it.config.type in setOf(
                        ProviderType.STREMIO,
                        ProviderType.UNIVERSAL_SCRAPER,
                        ProviderType.IPTV
                    )
                }
                val scaffolds = providers.size - working
                Text(
                    "$working fully working · $scaffolds plugin adapters (Phase 3)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showAbout = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("About StormStream")
                }
            }
        }
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear watch history?", fontWeight = FontWeight.Bold) },
            text = {
                Text("This will erase ${history.size} watch-history entries and reset all resume positions.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    confirmClearHistory = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") }
            }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("About StormStream", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "StormStream v0.2.0",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("A clean-room universal streaming app for Android.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    val prows = listOf(
                        "Stremio addons" to "✅ Working",
                        "Universal HTML/JSON scrapers" to "✅ Working",
                        "IPTV / M3U playlists" to "✅ Working",
                        "CloudStream / Vega / QuickJS" to "🔌 Phase 3",
                        "SkyStream / Sora / Aniyomi" to "🔌 Phase 3",
                        "Native .storm extensions" to "🔌 Phase 3",
                    )
                    prows.forEach { (name, status) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, style = MaterialTheme.typography.bodySmall)
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (status.startsWith("✅"))
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
        }
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
