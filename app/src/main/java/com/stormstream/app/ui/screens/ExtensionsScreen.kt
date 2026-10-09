package com.stormstream.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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

    var addDialog: AddDialogKind? by remember { mutableStateOf(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extensions", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = { addDialog = AddDialogKind.MENU }) {
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
                Text(
                    "Installed (${providers.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
            }

            if (providers.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                "No extensions yet",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Tap the + button to add a Stremio addon, M3U playlist, universal scraper, or extension repo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(providers.values.toList(), key = { it.config.id }) { p ->
                var enabled by remember(p.config.id, p.config.enabled) {
                    mutableStateOf(p.config.enabled)
                }
                ElevatedCard(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProviderBadge(p.config.type)
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.config.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                buildString {
                                    append(p.config.type.key)
                                    p.config.version?.let { append(" · v$it") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = enabled,
                                onCheckedChange = {
                                    enabled = it
                                    viewModel.toggleProviderEnabled(p.config.id, it)
                                }
                            )
                            IconButton(onClick = {
                                viewModel.uninstallProvider(p.config.id)
                            }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Uninstall",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            if (repos.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Added repos",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                }
                repos.forEach { (url, idx) ->
                    item {
                        ElevatedCard(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    idx.name ?: url,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (!idx.description.isNullOrBlank()) {
                                    Text(
                                        idx.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                idx.plugins.take(6).forEach { plug: RepoPlugin ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            "• ${plug.name}",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { viewModel.installPlugin(plug) }
                                        ) { Text("Install") }
                                    }
                                }
                                if (idx.plugins.size > 6) {
                                    Text(
                                        "+ ${idx.plugins.size - 6} more",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    when (addDialog) {
        AddDialogKind.MENU -> AddSourceMenu(
            onDismiss = { addDialog = null },
            onPick = { addDialog = it }
        )
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

enum class AddDialogKind { MENU, STREMIO, IPTV, SCRAPER, REPO }

@Composable
private fun ProviderBadge(type: ProviderType) {
    val scheme = MaterialTheme.colorScheme
    val (label, color, textColor) = when (type) {
        ProviderType.STREMIO -> Triple("ST", scheme.primary, scheme.onPrimary)
        ProviderType.UNIVERSAL_SCRAPER -> Triple("SC", scheme.secondary, scheme.onSecondary)
        ProviderType.CS3 -> Triple("CS3", scheme.tertiary, scheme.onTertiary)
        ProviderType.VEGA -> Triple("VG", scheme.error, scheme.onError)
        ProviderType.SKYSTREAM -> Triple("SK", scheme.primaryContainer, scheme.onPrimaryContainer)
        ProviderType.SORA -> Triple("SO", scheme.secondaryContainer, scheme.onSecondaryContainer)
        ProviderType.ANIYOMI -> Triple("AN", scheme.errorContainer, scheme.onErrorContainer)
        ProviderType.NUVIO -> Triple("NU", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        ProviderType.IPTV -> Triple("TV", scheme.primary, scheme.onPrimary)
        ProviderType.MANGA -> Triple("MG", scheme.secondary, scheme.onSecondary)
        ProviderType.STORM -> Triple("SS", scheme.tertiary, scheme.onTertiary)
    }
    Surface(color = color, shape = RoundedCornerShape(10.dp)) {
        Text(
            label,
            color = textColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AddSourceMenu(onDismiss: () -> Unit, onPick: (AddDialogKind) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add source", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                MenuItemBtn("Stremio addon (manifest URL)") { onPick(AddDialogKind.STREMIO) }
                MenuItemBtn("Universal JSON/HTML scraper") { onPick(AddDialogKind.SCRAPER) }
                MenuItemBtn("IPTV / M3U playlist") { onPick(AddDialogKind.IPTV) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                MenuItemBtn("Add extension repo (CloudStream / Vega / Storm)") {
                    onPick(AddDialogKind.REPO)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MenuItemBtn(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
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
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) { Text("Add") }
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
        title = { Text("Add IPTV playlist", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("M3U URL") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) onConfirm(name.trim(), url.trim())
                },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AddScraperDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var json by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add universal scraper", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = json,
                onValueChange = { json = it },
                label = { Text("JSON configuration") },
                placeholder = {
                    Text("""{"name":"MySite","baseUrl":"https://...","mode":"HTML",...}""")
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                maxLines = 14,
                shape = RoundedCornerShape(12.dp),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (json.isNotBlank()) onConfirm(json.trim()) },
                enabled = json.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
