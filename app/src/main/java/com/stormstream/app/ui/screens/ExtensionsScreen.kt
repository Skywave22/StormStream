package com.stormstream.app.ui.screens

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stormstream.app.R
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.InstalledExtension
import com.stormstream.app.data.RepoEntry
import com.stormstream.app.data.RepoPlugin
import com.stormstream.app.providers.plugin.PluginRepoManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    viewModel: AppViewModel = viewModel(),
) {
    val extensions by viewModel.installedExtensions.collectAsState()
    val errors by viewModel.providerErrors.collectAsState()
    val repoStates by viewModel.repoStates.collectAsState()
    val repoEntries by viewModel.repoEntries.collectAsState()
    val busy by viewModel.busy.collectAsState()

    var showAddMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<AddDialog?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extensions") },
                actions = {
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add source")
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Stremio addon (manifest URL)") },
                                onClick = { showAddMenu = false; dialog = AddDialog.Stremio },
                            )
                            DropdownMenuItem(
                                text = { Text("IPTV / M3U playlist") },
                                onClick = { showAddMenu = false; dialog = AddDialog.Iptv },
                            )
                            DropdownMenuItem(
                                text = { Text("Scraper (JSON config)") },
                                onClick = { showAddMenu = false; dialog = AddDialog.Scraper },
                            )
                            DropdownMenuItem(
                                text = { Text("JS plugin (.js or manifest URL)") },
                                onClick = { showAddMenu = false; dialog = AddDialog.JsPlugin },
                            )
                            DropdownMenuItem(
                                text = { Text("Extension repository") },
                                onClick = { showAddMenu = false; dialog = AddDialog.Repo },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                // ---------- installed ----------
                item {
                    Text(
                        text = "INSTALLED (${extensions.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                if (extensions.isEmpty()) {
                    item {
                        Text(
                            text = "No extensions installed yet. Tap + to add a source.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                items(extensions, key = { it.config.id }) { ext ->
                    ExtensionCard(
                        extension = ext,
                        typeLabel = viewModel.providerTypeLabel(ext.config.type),
                        error = errors[ext.config.id],
                        onToggle = { enabled -> viewModel.setExtensionEnabled(ext.config.id, enabled) },
                        onRemove = { viewModel.uninstallExtension(ext.config.id) },
                    )
                }

                // ---------- repositories ----------
                item {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Text(
                        text = "REPOSITORIES (${repoEntries.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                if (repoEntries.isEmpty()) {
                    item {
                        Text(
                            text = "No repositories added. Add one to browse community plugins.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                items(repoEntries, key = { it.url }) { entry: RepoEntry ->
                    RepoCard(
                        url = entry.url,
                        state = repoStates[entry.url],
                        onRefresh = { viewModel.refreshRepo(entry.url) },
                        onRemove = { viewModel.removeRepo(entry.url) },
                        onInstallPlugin = { plugin -> viewModel.installPlugin(plugin) },
                    )
                }
            }
        }
    }

    // ---------- dialogs ----------
    when (val d = dialog) {
        is AddDialog.Stremio -> UrlDialog(
            title = "Add Stremio addon",
            label = "manifest.json URL",
            placeholder = "https://v3-cinemeta.strem.io/manifest.json",
            onDismiss = { dialog = null },
            onConfirm = { viewModel.installStremio(it); dialog = null },
        )
        is AddDialog.Iptv -> IptvDialog(
            onDismiss = { dialog = null },
            onConfirm = { name, url -> viewModel.installIptv(name, url); dialog = null },
        )
        is AddDialog.Scraper -> JsonDialog(
            title = "Add scraper",
            label = "Scraper JSON config",
            onDismiss = { dialog = null },
            onConfirm = { viewModel.installScraper(it); dialog = null },
        )
        is AddDialog.JsPlugin -> JsPluginDialog(
            onDismiss = { dialog = null },
            onConfirm = { name, url -> viewModel.installJsPlugin(url, name.ifBlank { null }); dialog = null },
        )
        is AddDialog.Repo -> UrlDialog(
            title = "Add extension repository",
            label = "repo.json URL or github.com/owner/repo",
            placeholder = "https://raw.githubusercontent.com/.../repo.json",
            onDismiss = { dialog = null },
            onConfirm = { viewModel.addRepo(it); dialog = null },
        )
        null -> {}
    }
}

// ---------- installed extension card ----------

@Composable
private fun ExtensionCard(
    extension: InstalledExtension,
    typeLabel: String,
    error: String?,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (extension.config.icon != null) {
                    AsyncImage(
                        model = extension.config.icon,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(R.drawable.ic_placeholder),
                        error = painterResource(R.drawable.ic_placeholder),
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = extension.config.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(typeLabel)
                            if (extension.config.version != null) append(" · v${extension.config.version}")
                            if (extension.config.adult) append(" · 18+")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = extension.config.enabled,
                    onCheckedChange = onToggle,
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------- repository card ----------

@Composable
private fun RepoCard(
    url: String,
    state: PluginRepoManager.RepoState?,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onInstallPlugin: (RepoPlugin) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (state) {
                            is PluginRepoManager.RepoState.Ok ->
                                state.index.name.ifBlank { url }
                            else -> url
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when (state) {
                            is PluginRepoManager.RepoState.Ok ->
                                "${state.index.plugins.size} plugins"
                            is PluginRepoManager.RepoState.Loading -> "Loading…"
                            is PluginRepoManager.RepoState.Err -> "Failed: ${state.message}"
                            null -> "Loading…"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = when (state) {
                            is PluginRepoManager.RepoState.Err -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (state is PluginRepoManager.RepoState.Ok && state.index.plugins.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide" else "Browse")
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload repo")
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove repo",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (expanded && state is PluginRepoManager.RepoState.Ok) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                state.index.plugins.forEach { plugin ->
                    PluginRow(plugin = plugin, onInstall = { onInstallPlugin(plugin) })
                }
            }
        }
    }
}

@Composable
private fun PluginRow(plugin: RepoPlugin, onInstall: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        if (plugin.icon != null) {
            AsyncImage(
                model = plugin.icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = painterResource(R.drawable.ic_placeholder),
                error = painterResource(R.drawable.ic_placeholder),
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = plugin.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (plugin.providerType != null) append(plugin.providerType)
                    if (plugin.files.isNotEmpty()) {
                        if (plugin.providerType != null) append(" · ")
                        append("${plugin.files.size} files")
                    }
                    if (plugin.description != null) {
                        if (plugin.providerType != null || plugin.files.isNotEmpty()) append(" · ")
                        append(plugin.description.take(60))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(onClick = onInstall) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Install")
        }
    }
}

// ---------- dialogs ----------

private sealed interface AddDialog {
    data object Stremio : AddDialog
    data object Iptv : AddDialog
    data object Scraper : AddDialog
    data object JsPlugin : AddDialog
    data object Repo : AddDialog
}

@Composable
private fun UrlDialog(
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
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IptvDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add IPTV playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("M3U URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), url.trim()) },
                enabled = name.isNotBlank() && url.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun JsonDialog(
    title: String,
    label: String,
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
                placeholder = { Text("{ \"name\": \"MySite\", \"baseUrl\": \"https://…\", … }") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                maxLines = 12,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun JsPluginDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add JS plugin") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Plugin name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL of a .js file or a storm.plugin.json manifest") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), url.trim()) },
                enabled = url.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
