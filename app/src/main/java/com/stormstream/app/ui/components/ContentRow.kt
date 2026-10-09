package com.stormstream.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stormstream.app.data.HistoryEntry
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.MediaType

@Composable
fun ContentRow(
    title: String,
    subtitle: String? = null,
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    progressMap: Map<String, Float> = emptyMap(),
) {
    if (items.isEmpty()) return
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (items.any { it.type == MediaType.IPTV }) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items, key = { it.id + "@" + it.providerId }) { item ->
                    ChannelCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        modifier = Modifier.width(260.dp)
                    )
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items, key = { it.id + "@" + it.providerId }) { item ->
                    val key = "${item.providerId}:${item.id}"
                    PosterCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        progress = progressMap[key]
                    )
                }
            }
        }
    }
}

@Composable
fun ContinueWatchingRow(
    title: String,
    items: List<HistoryEntry>,
    onItemClick: (HistoryEntry) -> Unit,
    onRemove: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items, key = { it.key }) { entry ->
                ContinueWatchingCard(
                    entry = entry,
                    onClick = { onItemClick(entry) },
                    onRemove = { onRemove(entry) }
                )
            }
        }
    }
}
