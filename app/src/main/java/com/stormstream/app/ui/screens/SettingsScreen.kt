package com.stormstream.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormstream.app.BuildConfig
import com.stormstream.app.data.AppViewModel
import com.stormstream.app.data.THEME_DARK
import com.stormstream.app.data.THEME_LIGHT
import com.stormstream.app.data.THEME_SYSTEM

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel = viewModel(),
) {
    val theme by viewModel.settings.theme.collectAsState(initial = THEME_DARK)
    val hwdec by viewModel.settings.hwdec.collectAsState(initial = true)
    val speed by viewModel.settings.defaultSpeed.collectAsState(initial = 1.0)
    val autoplay by viewModel.settings.autoplayNext.collectAsState(initial = true)
    val showAdult by viewModel.settings.showAdult.collectAsState(initial = false)
    val subScale by viewModel.settings.subtitleScale.collectAsState(initial = 1.0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------- appearance ----------
            item {
                SettingsSection(title = "Appearance") {
                    Column(Modifier.selectableGroup()) {
                        ThemeRow("Dark", theme == THEME_DARK) {
                            viewModel.setTheme(THEME_DARK)
                        }
                        ThemeRow("Light", theme == THEME_LIGHT) {
                            viewModel.setTheme(THEME_LIGHT)
                        }
                        ThemeRow("System", theme == THEME_SYSTEM) {
                            viewModel.setTheme(THEME_SYSTEM)
                        }
                    }
                }
            }

            // ---------- player ----------
            item {
                SettingsSection(title = "Player (libmpv)") {
                    SwitchRow(
                        title = "Hardware decoding",
                        subtitle = "Let libmpv use the GPU decoder (recommended)",
                        checked = hwdec,
                        onCheckedChange = { viewModel.setHwdec(it) },
                    )
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        var speedDrag by remember { mutableStateOf<Float?>(null) }
                        val speedShown = speedDrag?.toDouble() ?: speed
                        Text(
                            text = "Default playback speed: ${"%.2f".format(speedShown).removeSuffix(".00")}x",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = speedShown.toFloat(),
                            onValueChange = { speedDrag = it },
                            onValueChangeFinished = {
                                viewModel.setDefaultSpeed(speedDrag?.toDouble() ?: speed)
                                speedDrag = null
                            },
                            valueRange = 0.5f..2f,
                            steps = 5,
                        )
                    }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        var subScaleDrag by remember { mutableStateOf<Float?>(null) }
                        val subScaleShown = subScaleDrag?.toDouble() ?: subScale
                        Text(
                            text = "Subtitle size: ${"%.0f".format(subScaleShown * 100)}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = subScaleShown.toFloat(),
                            onValueChange = { subScaleDrag = it },
                            onValueChangeFinished = {
                                viewModel.setSubtitleScale(subScaleDrag?.toDouble() ?: subScale)
                                subScaleDrag = null
                            },
                            valueRange = 0.5f..2f,
                            steps = 5,
                        )
                    }
                    SwitchRow(
                        title = "Auto-play next episode",
                        subtitle = "Continue to the next episode when one finishes",
                        checked = autoplay,
                        onCheckedChange = { viewModel.setAutoplayNext(it) },
                    )
                }
            }

            // ---------- content ----------
            item {
                SettingsSection(title = "Content") {
                    SwitchRow(
                        title = "Show adult extensions",
                        subtitle = "Include NSFW sources in home and search",
                        checked = showAdult,
                        onCheckedChange = { viewModel.setShowAdult(it) },
                    )
                }
            }

            // ---------- data ----------
            item {
                SettingsSection(title = "Data") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.clearCaches() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Clear caches")
                        }
                    }
                }
            }

            // ---------- about ----------
            item {
                SettingsSection(title = "About") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "StormStream",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        "v${BuildConfig.VERSION_NAME} · player: internal libmpv",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "One player (libmpv, bundled in the app), every source: " +
                                    "Stremio addons, universal scrapers, IPTV playlists and " +
                                    "JavaScript plugins running in the built-in QuickJS engine.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
private fun ThemeRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
