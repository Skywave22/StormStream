package com.stormstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.RepoIndex
import com.stormstream.app.data.RepoPlugin
import com.stormstream.app.providers.StreamProvider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    onBack: () -> Unit,
    viewModel: AppViewModel = viewModel(),
) {
    var tab by remember { mutableIntStateOf(0) }
    val installed by viewModel.providers.collectAsState()
    val repos by viewModel.repos.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun launch(label: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            busyLabel = label
            try { block() } finally { busy = false; busyLabel = "" }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Extensions", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            Column(Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, divider = {}) {
                    listOf("Installed", "Add URL", "Repos").forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i },
                            text = { Text(t, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) })
                    }
                }
                when (tab) {
                    0 -> InstalledList(installed = installed.values.toList(),
                        onRemove = { id -> viewModel.uninstallProvider(id) })
                    1 -> AddUrlPanel(
                        busy = busy,
                        onAddStremio = { url ->
                            launch("Adding Stremio addon") {
                                callbackToSuspend { done -> viewModel.installStremio(url, done) }
                            }
                        },
                        onAddScraper = { json ->
                            launch("Installing scraper") {
                                callbackToSuspend { done -> viewModel.installScraper(json, done) }
                            }
                        },
                        onAddIptv = { name, url ->
                            launch("Adding IPTV list") {
                                callbackToSuspend { done -> viewModel.installIptv(name, url, done) }
                            }
                        }
                    )
                    2 -> ReposPanel(
                        repos = repos,
                        busy = busy,
                        onAddRepo = { url ->
                            launch("Adding repo") {
                                callbackToSuspend { done -> viewModel.addRepo(url, done) }
                            }
                        },
                        onInstall = { plugin ->
                            launch("Installing ${plugin.name}") { viewModel.installPlugin(plugin) }
                        }
                    )
                }
            }

            if (busy) {
                Surface(color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(busyLabel, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** Bridge callback-style (onDone: (Boolean) -> Unit) calls to suspend functions. */
private suspend fun callbackToSuspend(block: ((Boolean) -> Unit) -> Unit): Boolean {
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        block { ok ->
            if (cont.isActive) cont.resume(kotlin.Result.success(ok)) { _, _, _ -> }
        }
    }.getOrDefault(false)
}

@Composable
private fun InstalledList(installed: List<StreamProvider>, onRemove: (String) -> Unit) {
    if (installed.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ExtensionOff, null, modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("No extensions installed", style = MaterialTheme.typography.titleMedium)
                Text("Add Stremio addons, JSON scrapers, or plugin repos via the other tabs.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(installed, key = { it.config.id }) { p ->
            val c = p.config
            Surface(shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        modifier = Modifier.size(44.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(c.name.first().uppercaseChar().toString(), fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium)
                        val meta = listOf(c.type.name.lowercase(), c.version).filterNotNull().joinToString(" · ")
                        Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { onRemove(c.id) }) {
                        Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddUrlPanel(
    onAddStremio: (String) -> Unit,
    onAddScraper: (String) -> Unit,
    onAddIptv: (String, String) -> Unit,
    busy: Boolean,
) {
    var stremioUrl by remember { mutableStateOf("") }
    var scraperJson by remember { mutableStateOf("") }
    var iptvName by remember { mutableStateOf("") }
    var iptvUrl by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Stremio addon", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = stremioUrl, onValueChange = { stremioUrl = it },
            label = { Text("Manifest URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(14.dp), placeholder = { Text("https://.../manifest.json") })
        Button(onClick = { onAddStremio(stremioUrl.trim()) },
            enabled = stremioUrl.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add Stremio addon")
        }

        HorizontalDivider()

        Text("Universal scraper (JSON)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = scraperJson, onValueChange = { scraperJson = it },
            label = { Text("Paste JSON rule") },
            modifier = Modifier.fillMaxWidth().height(140.dp), shape = RoundedCornerShape(14.dp))
        Button(onClick = { onAddScraper(scraperJson) },
            enabled = scraperJson.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Install scraper") }

        HorizontalDivider()

        Text("IPTV playlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = iptvName, onValueChange = { iptvName = it },
            label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value = iptvUrl, onValueChange = { iptvUrl = it },
            label = { Text("M3U URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(14.dp))
        Button(onClick = { onAddIptv(iptvName.trim(), iptvUrl.trim()) },
            enabled = iptvName.isNotBlank() && iptvUrl.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Add playlist") }
    }
}

@Composable
private fun ReposPanel(
    repos: Map<String, RepoIndex>,
    onAddRepo: (String) -> Unit,
    onInstall: (RepoPlugin) -> Unit,
    busy: Boolean,
) {
    var repoUrl by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Plugin repos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = repoUrl, onValueChange = { repoUrl = it },
            label = { Text("Repo index URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(14.dp))
        Button(onClick = { onAddRepo(repoUrl.trim()) },
            enabled = repoUrl.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Add repo") }

        if (repos.isEmpty()) {
            Text("No repos added. Add a repo index URL to browse plugins.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        } else {
            repos.forEach { (_, idx) ->
                Text(idx.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                idx.plugins.forEach { plugin ->
                    Surface(shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(plugin.name, fontWeight = FontWeight.SemiBold)
                                Text("v${plugin.version}" + (plugin.providerType?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (plugin.tvTypes.isNotEmpty()) {
                                    Text(plugin.tvTypes.joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1)
                                }
                            }
                            Button(onClick = { onInstall(plugin) },
                                enabled = !busy, shape = RoundedCornerShape(12.dp)) {
                                Text("Install", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
