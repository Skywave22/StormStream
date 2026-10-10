package com.stormstream.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stormstream.app.data.StreamSource

/**
 * Bottom-sheet content listing resolved streams for an item/episode.
 * Each row shows quality, name and stream type; tapping plays that source.
 */
@Composable
fun StreamPickerContent(
    streams: List<StreamSource>,
    onSelect: (StreamSource) -> Unit,
    modifier: Modifier = Modifier,
    providerName: (String) -> String = { it },
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Choose a stream",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(streams, key = { it.url + it.name }) { source ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(source) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = source.quality ?: source.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(source.name)
                                append(" · ")
                                append(source.type.name)
                                if (source.subtitles.isNotEmpty()) {
                                    append(" · ${source.subtitles.size} subtitle${if (source.subtitles.size > 1) "s" else ""}")
                                }
                                val provider = providerName(source.providerId)
                                if (provider.isNotBlank()) {
                                    append(" · ")
                                    append(provider)
                                }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (source.quality != null) {
                        QualityChip(source.quality.uppercase())
                    }
                }
            }
        }
    }
}
