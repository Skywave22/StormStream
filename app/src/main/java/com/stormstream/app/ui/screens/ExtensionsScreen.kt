package com.stormstream.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.ProviderType
import com.stormstream.app.data.RepoPlugin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(viewModel: AppViewModel = viewModel()) {
    val providers by viewModel.providers.collectAsState()
    val repos by viewModel.repos.collectAsState()

    var showAddMenu by remember { mutableStateOf(false) }
    var addDialog: AddDialogKind? by remember { mutableStateOf(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extensions") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = { showAddMenu = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addDialog = AddDialogKind.REPO },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Extension, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add repo")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("Installed providers (${providers.size})",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            items(providers.values.toList(), key = { it.config.id }) { p ->
                ElevatedCard(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ProviderBadge(p.config.type)
                        Column(Modifier.weight(1f)) {
                            Text(p.config.name, style = MaterialTheme.typography.titleSmall)
                            Text(p.config.type.key,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("v${p.config.version ?: "?"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (repos.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Added repos", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                }
                repos.forEach { (url, idx) ->
                    item {
                        ElevatedCard(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(idx.name ?: url, style = MaterialTheme.typography.titleSmall)
                                if (idx.description != null) {
                                    Text(idx.description, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(8.dp))
                                idx.plugins.take(5).forEach { plug: RepoPlugin ->
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                        Text("• ${plug.name}", style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { viewModel.installPlugin(plug) }) {
                                            Text("Install")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddMenu) {
        AddSourceMenu(
            onDismiss = { showAddMenu = false },
            onPick = { addDialog = it; showAddMenu = false }
        )
    }

    when (addDialog) {
        AddDialogKind.STREMIO -> AddUrlDialog(
            title = "Add Stremio addon",
            label = "manifest.json URL",
            placeholder = "https://v3-cinemeta.strem.io/manifest.json",
            onDismiss = { addDialog = null },
            onConfirm = { url -> viewModel.installStremio(url) { addDialog = null } }
        )
        AddDialogKind.IPTV -> AddIptvDialog(
            onDismiss = { addDialog = null },
            onConfirm = { name, url -> viewModel.installIptv(name, url) { addDialog = null } }
        )
        AddDialogKind.SCRAPER -> AddScraperDialog(
            onDismiss = { addDialog = null },
            onConfirm = { json -> viewModel.installScraper(json) { addDialog = null } }
        )
        AddDialogKind.REPO -> AddUrlDialog(
            title = "Add extension repo",
            label = "repo.json URL (or github.com/owner/repo)",
            placeholder = "https://raw.githubusercontent.com/.../repo.json",
            onDismiss = { addDialog = null },
            onConfirm = { url -> viewModel.addRepo(url) { addDialog = null } }
        )
        null -> {}
    }
}

enum class AddDialogKind { STREMIO, IPTV, SCRAPER, REPO }

@Composable
private fun ProviderBadge(type: ProviderType) {
    val scheme = MaterialTheme.colorScheme
    val (label, color, textColor) = when (type) {
        ProviderType.STREMIO -> Triple("ST", scheme.primary, scheme.onPrimary)
        ProviderType.UNIVERSAL_SCRAPER -> Triple("SC", scheme.secondary, scheme.onSecondary)
        ProviderType.CS3 -> Triple("CS", scheme.tertiary, scheme.onTertiary)
        ProviderType.VEGA -> Triple("VG", scheme.error, scheme.onError)
        ProviderType.SKYSTREAM -> Triple("SK", scheme.primaryContainer, scheme.onPrimaryContainer)
        ProviderType.SORA -> Triple("SO", scheme.secondaryContainer, scheme.onSecondaryContainer)
        ProviderType.ANIYOMI -> Triple("AN", scheme.errorContainer, scheme.onErrorContainer)
        ProviderType.NUVIO -> Triple("NU", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        ProviderType.IPTV -> Triple("TV", scheme.primary, scheme.onPrimary)
        ProviderType.MANGA -> Triple("MG", scheme.secondary, scheme.onSecondary)
        ProviderType.STORM -> Triple("SS", scheme.tertiary, scheme.onTertiary)
    }
    Surface(color = color, shape = RoundedCornerShape(8.dp)) {
        Text(label,
            color = textColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun AddSourceMenu(onDismiss: () -> Unit, onPick: (AddDialogKind) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add source") },
        text = {
            Column {
                MenuItemBtn("Stremio addon (manifest URL)") { onPick(AddDialogKind.STREMIO) }
                MenuItemBtn("Universal JSON/HTML scraper") { onPick(AddDialogKind.SCRAPER) }
                MenuItemBtn("IPTV / M3U playlist") { onPick(AddDialogKind.IPTV) }
                Divider()
                MenuItemBtn("CloudStream / Vega / SkyStream / Sora / Aniyomi / Nuvio / Storm — add via repo") {
                    onPick(AddDialogKind.REPO)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MenuItemBtn(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AddUrlDialog(
    title: String,
    label: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AddIptvDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add IPTV playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Playlist name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = url, onValueChange = { url = it },
                    label = { Text("M3U URL") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && url.isNotBlank()) onConfirm(name.trim(), url.trim())
            }, enabled = name.isNotBlank() && url.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AddScraperDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var json by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add universal scraper") },
        text = {
            OutlinedTextField(
                value = json, onValueChange = { json = it },
                label = { Text("JSON configuration") },
                placeholder = { Text("""{"name":"MySite","baseUrl":"https://...","mode":"HTML",...}""") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                maxLines = 12,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (json.isNotBlank()) onConfirm(json.trim()) },
                enabled = json.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
