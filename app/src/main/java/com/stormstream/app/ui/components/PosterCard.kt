package com.stormstream.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.stormstream.app.data.MediaItem

@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(130.dp)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
        ) {
            if (item.posterUrl != null) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (item.year != null) {
            Text(
                item.year.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ChannelCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (item.posterUrl != null) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
                )
            }
            Column {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (item.group != null) {
                    Text(
                        item.group,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
