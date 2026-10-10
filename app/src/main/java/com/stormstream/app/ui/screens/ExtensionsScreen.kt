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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.stormstream.app.data.PluginSettingField
import androidx.compose.material.icons.filled.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    viewModel: AppViewModel = viewModel(),
) {
    val extensions by viewModel.installedExtensions.collectAsState()
    val errors by viewModel.providerErrors.collectAsState()
    val repoStates by viewModel.repoStates.collectAsState()
    val repoEntries by viewModel.repoEntries.collectAsState(initial = emptyList())
    val busy by viewModel.busy.collectAsState()

    var showAddMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<AddDialog?>(null) }
    var settingsFor by remember { mutableStateOf<String?>(null) }

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
                                text = { Text("SkyStream extension (.sky)") },
                                onClick = { showAddMenu = false; dialog = AddDialog.SkyStream },
                            )
                            DropdownMenuItem(
                                text = { Text("Nuvio extension (manifest URL)") },
                                onClick = { showAddMenu = false; dialog = AddDialog.Nuvio },
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
                        hasSettings = ext.config.type == com.stormstream.app.data.ProviderType.SKYSTREAM ||
                            ext.config.type == com.stormstream.app.data.ProviderType.NUVIO ||
                            ext.config.type == com.stormstream.app.data.ProviderType.JS,
                        onOpenSettings = { settingsFor = ext.config.id },
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

    // ---------- extension settings dialog ----------
    val settingsProviderId = settingsFor
    if (settingsProviderId != null) {
        ExtensionSettingsDialog(
            providerId = settingsProviderId,
            providerName = viewModel.providerName(settingsProviderId),
            viewModel = viewModel,
            onDismiss = { settingsFor = null },
        )
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
        is AddDialog.SkyStream -> UrlDialog(
            title = "Add SkyStream extension",
            label = ".sky package or plugin.js URL",
            placeholder = "https://…/plugin.sky",
            onDismiss = { dialog = null },
            onConfirm = { viewModel.installSkyStream(it); dialog = null },
        )
        is AddDialog.Nuvio -> UrlDialog(
            title = "Add Nuvio extension",
            label = "manifest.json URL",
            placeholder = "https://…/manifest.json",
            onDismiss = { dialog = null },
            onConfirm = { viewModel.installNuvio(it); dialog = null },
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
    hasSettings: Boolean = false,
    onOpenSettings: (() -> Unit)? = null,
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
                if (hasSettings && onOpenSettings != null) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Extension settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
    data object SkyStream : AddDialog
    data object Nuvio : AddDialog
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

@Composable
private fun ExtensionSettingsDialog(
    providerId: String,
    providerName: String,
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
) {
    var fields by remember { mutableStateOf<List<PluginSettingField>?>(null) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(providerId) {
        try {
            fields = viewModel.settingsFieldsFor(providerId)
            values = viewModel.settingsValuesFor(providerId)
        } catch (e: Throwable) {
            loadError = e.message ?: "Failed to load settings"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$providerName settings") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when {
                    loadError != null -> Text(
                        loadError!!,
                        color = MaterialTheme.colorScheme.error,
                    )
                    fields == null -> Text("Loading…")
                    fields!!.isEmpty() -> Text("This extension has no settings.")
                    else -> fields!!.forEach { field ->
                        when (field.type) {
                            "info" -> {
                                if (field.title.isNotBlank()) {
                                    Text(
                                        text = field.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(vertical = 8.dp),
                                    )
                                }
                                if (field.description != null) {
                                    Text(
                                        text = field.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            "bool" -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = field.title,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = (values[field.key] ?: field.defaultValue) == "true",
                                        onCheckedChange = { on ->
                                            values = values + (field.key to on.toString())
                                            viewModel.setSettingFor(providerId, field.key, on.toString())
                                        },
                                    )
                                }
                            }
                            "select" -> {
                                var expanded by remember { mutableStateOf(false) }
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    Text(text = field.title)
                                    OutlinedButton(
                                        onClick = { expanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            values[field.key] ?: field.defaultValue.ifBlank { "Select…" },
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false },
                                    ) {
                                        field.options.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option.label) },
                                                onClick = {
                                                    expanded = false
                                                    values = values + (field.key to option.value)
                                                    viewModel.setSettingFor(providerId, field.key, option.value)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            else -> {
                                OutlinedTextField(
                                    value = values[field.key] ?: field.defaultValue,
                                    onValueChange = { v ->
                                        values = values + (field.key to v)
                                        viewModel.setSettingFor(providerId, field.key, v)
                                    },
                                    label = { Text(field.title) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
